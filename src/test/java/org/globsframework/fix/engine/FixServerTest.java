package org.globsframework.fix.engine;

import org.globsframework.core.metamodel.GlobModel;
import org.globsframework.core.metamodel.impl.DefaultGlobModel;
import org.globsframework.core.model.Glob;
import org.globsframework.core.model.MutableGlob;
import org.globsframework.core.utils.Ref;
import org.globsframework.fix.HeaderType;
import org.globsframework.fix.TrailerType;
import org.globsframework.fix.deserializer.*;
import org.globsframework.fix.dictionary.FixModel;
import org.globsframework.fix.dictionary.admin.LogonType;
import org.globsframework.fix.dictionary.xml.FieldFactoryImpl;
import org.globsframework.fix.dictionary.xml.ReadFixDictionary;
import org.globsframework.fix.fix44.app.QuoteRequestType;
import org.globsframework.fix.fix44.app.QuoteResponseType;
import org.globsframework.fix.serializer.*;
import org.globsframework.json.GSonUtils;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

class FixServerTest {

    private static final Logger log = LoggerFactory.getLogger(FixServerTest.class);

    @Test
    void clientServer() throws IOException, ExecutionException, InterruptedException, TimeoutException {
        final FixModel fixModel = ReadFixDictionary.parse("fix44", () ->
                new InputStreamReader(getClass().getClassLoader().getResourceAsStream("FIX44.xml"),
                        StandardCharsets.UTF_8), new FieldFactoryImpl());

        final GlobModel globModel = new DefaultGlobModel(QuoteRequestType.TYPE, QuoteResponseType.TYPE);

        final DeserializerFixReaderBuilder deserializerFixReaderBuilder = DeserializerFixReaderBuilder.create(fixModel, globModel, HeaderType.TYPE, TrailerType.TYPE);
        final SerializerFixWriterBuilder serializerFixWriterBuilder = SerializerFixWriterBuilder.create(fixModel, globModel, HeaderType.TYPE, TrailerType.TYPE);
        final HeaderDesc headerDesc = HeaderDesc.create(HeaderType.TYPE);

        final ExecutorService executorService = Executors.newCachedThreadPool();
        final ScheduledExecutorService scheduledExecutorService = Executors.newSingleThreadScheduledExecutor();
        final BasicMsgSeqProvider serverMsgSeqProvider = new BasicMsgSeqProvider();
        FixSessionImpl.ClientSeqMsgId serverSeqMsgId = new InMemoryClientSeqMsgId();
        final DefaultSerializerProvider serializerProvider =
                new DefaultSerializerProvider(deserializerFixReaderBuilder, serializerFixWriterBuilder, headerDesc);

        final NewAcceptorFixConnectionImpl acceptorFixConnection =
                new NewAcceptorFixConnectionImpl(executorService, scheduledExecutorService, 49, 56, (byte) 0x1,
                        serializerProvider,
                                (String senderCompID, String targetCompID) -> new CacheProvider.SeqNumAndCache(NoCachedData.INSTANCE,
                                        serverMsgSeqProvider, serverSeqMsgId),
                                new ServerUserLogonSessionFactory(scheduledExecutorService));
        final FixServer fixServer = new FixServer("0.0.0.0", 0, new FixConnectionFactory(acceptorFixConnection, new LoggerPublish()));

        executorService.submit(fixServer::processConnections);

        final int port = fixServer.getPort();

        final BasicMsgSeqProvider clientMsgSeqProvider = new BasicMsgSeqProvider();
        FixSessionImpl.ClientSeqMsgId clientSeqMsgId = new InMemoryClientSeqMsgId();
        Ref<CompletableFuture<TestUserClientLogonSession>> testUserClientLogonSessionRef = new Ref<>();
        final ClientUserLogonSessionFactory userLogonSessionFactory = new ClientUserLogonSessionFactory(testUserClientLogonSession -> {
            testUserClientLogonSessionRef.get().complete(testUserClientLogonSession);
        });
        final FixClient fixClient = new FixClient("localhost", port,
                new FixConnectionFactory(
                        new NewInitiatorFixConnectionImpl(executorService, scheduledExecutorService, userLogonSessionFactory,
                                (String senderCompID, String targetCompID) -> new CacheProvider.SeqNumAndCache(NoCachedData.INSTANCE,
                                        clientMsgSeqProvider, clientSeqMsgId),
                                serializerProvider,
                                HeaderDesc.create(HeaderType.TYPE)
                        ),
                        new LoggerPublish()));

        final var priceListener = new TestUserClientLogonSession.PriceListener() {
            CompletableFuture<List<String>> actualPrices;
            List<String> prices = new ArrayList<>();

            @Override
            public void priceChanged(String str, String p) {
                log.info("EUR price changed: " + str + " " + p);
                prices.add(p);
                if (prices.size() == 3) {
                    actualPrices.complete(prices);
                    prices = new ArrayList<>();
                    actualPrices = null;
                }
            }
        };
        {
            testUserClientLogonSessionRef.set(new CompletableFuture<>());
            fixClient.connect();

            final TestUserClientLogonSession testUserClientLogonSession = testUserClientLogonSessionRef.get().get(10, TimeUnit.MILLISECONDS);

            priceListener.actualPrices = new CompletableFuture<>();
            testUserClientLogonSession.subscribe("EUR", priceListener);
            System.out.println("Prices: " + priceListener.actualPrices.get(100, TimeUnit.SECONDS));

            fixClient.disconnect().join();
        }
        priceListener.actualPrices = new CompletableFuture<>();
        {
            testUserClientLogonSessionRef.set(new CompletableFuture<>());
            fixClient.connect();

            final TestUserClientLogonSession testUserClientLogonSession = testUserClientLogonSessionRef.get().get(10, TimeUnit.MILLISECONDS);

            priceListener.actualPrices = new CompletableFuture<>();
            testUserClientLogonSession.subscribe("EUR", priceListener);
            System.out.println("Prices: " + priceListener.actualPrices.get(100, TimeUnit.SECONDS));

            fixClient.disconnect();
        }

    }

    interface Pricer {
        interface Price {
            void price(String value);
        }

        void subscribe(String symbol, Price price);
    }

    static class PricerImpl implements Pricer {
        final ScheduledExecutorService executorService;

        public PricerImpl(ScheduledExecutorService executorService) {
            this.executorService = executorService;
        }

        @Override
        public void subscribe(String symbol, Price price) {
            AtomicInteger count = new AtomicInteger(0);
            executorService.scheduleAtFixedRate(() -> {
                price.price(String.valueOf(System.currentTimeMillis()));
                if (count.incrementAndGet() == 3) {
                    throw new RuntimeException("Stop");
                }
            }, 0, 100, TimeUnit.MILLISECONDS);
        }
    }

    static public class InMemoryClientSeqMsgId implements FixSessionImpl.ClientSeqMsgId {
        private final AtomicInteger currentSeqNum = new AtomicInteger(0);
        @Override
        public int next(int expectedNext) {
            final int i = currentSeqNum.incrementAndGet();
            if (i != expectedNext) {
                throw new RuntimeException("invalide state " + i + " was expected but gor " + expectedNext);
            }
            return i + 1;
        }

        @Override
        public int current() {
            return currentSeqNum.get();
        }

        @Override
        public void reset(int lastReceived) {
            currentSeqNum.set(lastReceived);
        }
    }

    interface Connected {
        void connected(String targetCompID, TestUserClientLogonSession.ClientUserSession clientUserSession);
    }


    public static class ClientUserLogonSessionFactory implements UserLogonSessionFactory {
        private final NotifyNewClient notifyNewClient;


        interface NotifyNewClient {
            void newClient(TestUserClientLogonSession testUserClientLogonSession);
        }

        ClientUserLogonSessionFactory(NotifyNewClient notifyNewClient) {
            this.notifyNewClient = notifyNewClient;
        }

        @Override
        public FixSessionImpl.UserLogonSession create(Shutdown shutdown) {
            TestUserClientLogonSession testUserClientLogonSession =
                    new TestUserClientLogonSession(shutdown, "ZF", "AF");
            notifyNewClient.newClient(testUserClientLogonSession);
            return testUserClientLogonSession;
        }
    }

    public static class TestUserClientLogonSession implements FixSessionImpl.UserLogonSession, Connected {
        private final Shutdown shutdown;
        private final String senderCompID;
        private final String targetCompoID;
        private final Map<String, PriceListener> listeners = new HashMap<>();

        public TestUserClientLogonSession(Shutdown shutdown,
                                          String senderCompID, String targetCompoID) {
            this.shutdown = shutdown;
            this.senderCompID = senderCompID;
            this.targetCompoID = targetCompoID;
        }

        @Override
        public FixSessionImpl.UserSession initiator() {
            return new ClientUserSession(this);
        }

        @Override
        public FixSessionImpl.UserSession acceptor(String senderCompID, String targetCompID) {
            throw new RuntimeException("Expected to be the initiator");
        }

        public void subscribe(String sym, PriceListener priceListener) {
            listeners.put(sym, priceListener);
        }

        @Override
        public void connected(String targetCompID, ClientUserSession clientUserSession) {
//            log.info("Connect " + targetCompID + " register " + listeners.size() + " listener");
//            for (Map.Entry<String, PriceListener> stringPriceListenerEntry : listeners.entrySet()) {
//                log.info("Register " + stringPriceListenerEntry.getKey());
//                clientUserSession.subscribe(stringPriceListenerEntry.getKey(), stringPriceListenerEntry.getValue());
//            }
        }

        interface PriceListener {
            void priceChanged(String symbol, String bidPx);
        }


        private class ClientUserSession implements FixSessionImpl.UserSession {
            private final Connected connected;

            public ClientUserSession(Connected connected) {
                this.connected = connected;
            }


            @Override
            public CompletableFuture<Void> logout() {
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public Glob getLogon() {
                return LogonType.create(1000);
            }

            @Override
            public Glob getHeader() {
                return HeaderType.create(senderCompID, targetCompoID);
            }

            @Override
            public FixSessionImpl.AppMessageReceiver connected(FixMessageValue logon, FixWriter appWriter) {
                synchronized (this) {
                    connected.connected(targetCompoID, this);
                    for (String symbol : listeners.keySet()) {
                        appWriter.write(getHeader().duplicate(), QuoteRequestType.TYPE.instantiate()
                                        .set(QuoteRequestType.quoteReqID, symbol)
                                , null);
                    }
                }
                return new FixSessionImpl.AppMessageReceiver() {
                    @Override
                    public void messages(FixMessageValue fixMessageValue) {
                        if (fixMessageValue.message() != null) {
                            final Glob message = fixMessageValue.message();
                            if (message.getType() == QuoteResponseType.TYPE) {
                                final String quoteRespId = message.get(QuoteResponseType.quoteRespID);
                                final String bidPx = message.get(QuoteResponseType.bidPx);
                                final PriceListener priceListener = listeners.get(quoteRespId);
                                if (priceListener != null) {
                                    priceListener.priceChanged(quoteRespId, bidPx);
                                }
                            }
                        }
                    }
                };
            }
        }
    }

    public static class LoggerPublish implements Publish {
        @Override
        public void publish(byte[] data, int offset, int length) {
            log.info("Publishing " + new String(data, offset, length));
        }
    }

    //------------------------- Acceptor side


    public static class ServerUserLogonSessionFactory implements UserLogonSessionFactory {
        private final ScheduledExecutorService scheduledExecutorService;

        public ServerUserLogonSessionFactory(ScheduledExecutorService scheduledExecutorService) {
            this.scheduledExecutorService = scheduledExecutorService;
        }

        @Override
        public FixSessionImpl.UserLogonSession create(Shutdown shutdown) {
            return new TestUserServerLogonSession(shutdown, new PricerImpl(scheduledExecutorService));
        }
    }

    private static class TestUserServerLogonSession implements FixSessionImpl.UserLogonSession {
        private final Shutdown shutdown;
        private final Pricer pricer;

        public TestUserServerLogonSession(Shutdown shutdown, Pricer pricer) {
            this.shutdown = shutdown;
            this.pricer = pricer;
        }

        @Override
        public FixSessionImpl.UserSession initiator() {
            throw new RuntimeException("Pricer side is acceptor");
        }

        @Override
        public FixSessionImpl.UserSession acceptor(String senderCompID, String targetCompID) {
            // accept connection en inverse sender and target
            return new ServerPricerUserSession(targetCompID, senderCompID);
        }

        private class ServerPricerUserSession implements FixSessionImpl.UserSession {

            private final String senderCompID;
            private final String targetCompID;

            public ServerPricerUserSession(String senderCompID, String targetCompID) {
                this.senderCompID = senderCompID;
                this.targetCompID = targetCompID;
            }

            public FixSessionImpl.AppMessageReceiver connected(FixMessageValue logon, FixWriter appWriter) {
                return new FixSessionImpl.AppMessageReceiver() {
                    @Override
                    public void messages(FixMessageValue fixMessageValue) {
                        final Glob message = fixMessageValue.message();
                        log.info("Got message " + GSonUtils.encode(message) + " " + GSonUtils.encode(fixMessageValue.header()));
                        if (message.getType() == QuoteRequestType.TYPE) {
                            final String quoteReqId = message.get(QuoteRequestType.quoteReqID);
                            pricer.subscribe(quoteReqId, value -> {
                                appWriter.write(getHeader(),
                                        QuoteResponseType.TYPE.instantiate()
                                                .set(QuoteResponseType.quoteRespID, quoteReqId)
                                                .set(QuoteResponseType.bidPx, value),
                                        null);
                            });
                        }
                    }
                };
            }

            @Override
            public CompletableFuture<Void> logout() {
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public Glob getLogon() {
                return LogonType.create(1000);
            }

            @Override
            public MutableGlob getHeader() {
                return HeaderType.create(senderCompID, targetCompID);
            }
        }
    }

}