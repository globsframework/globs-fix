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
        final NewAcceptorFixConnectionImpl acceptorFixConnection =
                new NewAcceptorFixConnectionImpl(executorService, scheduledExecutorService, 49, 56, (byte) 0x1,
                        new MyPerTargetBuilder(deserializerFixReaderBuilder, serializerFixWriterBuilder, headerDesc,
                                () -> new CacheProvider.SeqNumAndCache(NoCachedData.INSTANCE, serverMsgSeqProvider),
                                new ServerUserLogonSessionFactory(scheduledExecutorService)));
        final FixServer fixServer = new FixServer("0.0.0.0", 0, new FixConnectionFactory(acceptorFixConnection, new LoggerPublish()));

        executorService.submit(fixServer::processConnections);

        final int port = fixServer.getPort();

        final BasicMsgSeqProvider clientMsgSeqProvider = new BasicMsgSeqProvider();
        Ref<CompletableFuture<TestUserClientLogonSession>> testUserClientLogonSessionRef = new Ref<>();
        final ClientUserLogonSessionFactory userLogonSessionFactory = new ClientUserLogonSessionFactory(clientMsgSeqProvider, testUserClientLogonSession -> {
            testUserClientLogonSessionRef.get().complete(testUserClientLogonSession);
        });
        final FixClient fixClient = new FixClient("localhost", port,
                new FixConnectionFactory(
                        new NewInitiatorFixConnectionImpl(executorService, scheduledExecutorService, userLogonSessionFactory,
                                () -> new CacheProvider.SeqNumAndCache(NoCachedData.INSTANCE, clientMsgSeqProvider),
                                DeserializerFixReaderBuilder.create(fixModel, globModel, HeaderType.TYPE, TrailerType.TYPE),
                                SerializerFixWriterBuilder.create(fixModel, globModel, HeaderType.TYPE, TrailerType.TYPE),
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

            fixClient.disconnect();
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

    private static class TestUserServerLogonSession implements FixSessionImpl.UserLogonSession {
        private final FixWriter writer;
        private final Shutdown shutdown;
        private final Pricer pricer;
        private final String senderCompID;
        private final String targetCompoID;

        public TestUserServerLogonSession(FixWriter writer, Shutdown shutdown, Pricer pricer, String senderCompID, String targetCompoID) {
            this.writer = writer;
            this.shutdown = shutdown;
            this.pricer = pricer;

            this.senderCompID = senderCompID;
            this.targetCompoID = targetCompoID;
        }

        @Override
        public FixSessionImpl.UserSession initiator() {
            throw new RuntimeException("Pricer side is acceptor");
        }

        @Override
        public FixSessionImpl.UserSession acceptor(FixMessageValue loggon) {
            // check logon
            return new ServerPricerUserSession();
        }

        private class ServerPricerUserSession implements FixSessionImpl.UserSession {
            @Override
            public void messages(FixMessageValue fixMessageValue) {
                final Glob message = fixMessageValue.message();
                log.info("Got message " + GSonUtils.encode(message) + " " + GSonUtils.encode(fixMessageValue.header()));
                if (message.getType() == QuoteRequestType.TYPE) {
                    final String quoteReqId = message.get(QuoteRequestType.quoteReqID);
                    pricer.subscribe(quoteReqId, value -> {
                        writer.write(getHeader(),
                                QuoteResponseType.TYPE.instantiate()
                                        .set(QuoteResponseType.quoteRespID, quoteReqId)
                                        .set(QuoteResponseType.bidPx, value),
                                null);
                    });
                }
            }

            @Override
            public CompletableFuture<Void> logout() {
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public Glob getLoggon() {
                return LogonType.create(1000);
            }

            @Override
            public int getSeqMsg() {
                return 1;
            }

            @Override
            public MutableGlob getHeader() {
                return HeaderType.create(senderCompID, targetCompoID);
            }

            @Override
            public void resetNext() {

            }

            @Override
            public void connected(FixMessageValue read) {

            }
        }
    }

    interface Connected {
        void connected(String targetCompID, TestUserClientLogonSession.ClientUserSession clientUserSession);
    }


    public static class TestUserClientLogonSession implements FixSessionImpl.UserLogonSession, Connected {
        private final MsgSeqProvider clientMsgSeqProvider;
        private final FixWriter writer;
        private final Shutdown shutdown;
        private final String senderCompID;
        private final String targetCompoID;
        private final Map<String, PriceListener> listeners = new HashMap<>();

        public TestUserClientLogonSession(MsgSeqProvider clientMsgSeqProvider, FixWriter writer, Shutdown shutdown,
                                          String senderCompID, String targetCompoID) {
            this.clientMsgSeqProvider = clientMsgSeqProvider;
            this.writer = writer;
            this.shutdown = shutdown;
            this.senderCompID = senderCompID;
            this.targetCompoID = targetCompoID;
        }

        @Override
        public FixSessionImpl.UserSession initiator() {
            return new ClientUserSession(this);
        }

        @Override
        public FixSessionImpl.UserSession acceptor(FixMessageValue loggon) {
            throw new RuntimeException("Expected to be the initiator");
        }

        public void subscribe(String sym, PriceListener priceListener) {
            listeners.put(sym, priceListener);
        }

        @Override
        public void connected(String targetCompID, ClientUserSession clientUserSession) {
            log.info("Connect " + targetCompID + " register " + listeners.size() + " listener");
            for (Map.Entry<String, PriceListener> stringPriceListenerEntry : listeners.entrySet()) {
                log.info("Register " + stringPriceListenerEntry.getKey());
                clientUserSession.subscribe(stringPriceListenerEntry.getKey(), stringPriceListenerEntry.getValue());
            }
        }

        interface PriceListener {
            void priceChanged(String symbol, String bidPx);
        }


        private class ClientUserSession implements FixSessionImpl.UserSession {
            private final Connected connected;

            public ClientUserSession(Connected connected) {
                this.connected = connected;
            }

            public void subscribe(String symbol, PriceListener priceListener) {
                writer.write(getHeader().duplicate(), QuoteRequestType.TYPE.instantiate()
                                .set(QuoteRequestType.quoteReqID, symbol)
                        , null);
            }

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

            @Override
            public CompletableFuture<Void> logout() {
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public Glob getLoggon() {
                return LogonType.create(1000);
            }

            @Override
            public int getSeqMsg() {
                return clientMsgSeqProvider.curent();
            }

            @Override
            public Glob getHeader() {
                return HeaderType.create(senderCompID, targetCompoID);
            }

            @Override
            public void resetNext() {

            }

            @Override
            public void connected(FixMessageValue read) {
                connected.connected(targetCompoID, this);
            }
        }
    }

    public static class ServerUserLogonSessionFactory implements UserLogonSessionFactory {
        private final ScheduledExecutorService scheduledExecutorService;

        public ServerUserLogonSessionFactory(ScheduledExecutorService scheduledExecutorService) {
            this.scheduledExecutorService = scheduledExecutorService;
        }

        @Override
        public FixSessionImpl.UserLogonSession create(FixWriter writer, Shutdown shutdown) {
            return new TestUserServerLogonSession(writer, shutdown, new PricerImpl(scheduledExecutorService), "AF", "ZF");
        }
    }

    public static class ClientUserLogonSessionFactory implements UserLogonSessionFactory {
        private final MsgSeqProvider clientMsgSeqProvider;
        private final NotifyNewClient notifyNewClient;


        interface NotifyNewClient {
            void newClient(TestUserClientLogonSession testUserClientLogonSession);
        }

        ClientUserLogonSessionFactory(MsgSeqProvider clientMsgSeqProvider, NotifyNewClient notifyNewClient) {
            this.clientMsgSeqProvider = clientMsgSeqProvider;
            this.notifyNewClient = notifyNewClient;
        }

        @Override
        public FixSessionImpl.UserLogonSession create(FixWriter writer, Shutdown shutdown) {
            TestUserClientLogonSession testUserClientLogonSession =
                    new TestUserClientLogonSession(clientMsgSeqProvider, writer, shutdown, "ZF", "AF");
            notifyNewClient.newClient(testUserClientLogonSession);
            return testUserClientLogonSession;
        }
    }

    public static class LoggerPublish implements Publish {
        @Override
        public void publish(byte[] data, int offset, int length) {
            log.info("Publishing " + new String(data, offset, length));
        }
    }

    public static class MyPerTargetBuilder implements NewAcceptorFixConnectionImpl.PerTargetBuilder {
        private final DeserializerFixReaderBuilder deserializerFixReaderBuilder;
        private final SerializerFixWriterBuilder serializerFixWriterBuilder;
        private final HeaderDesc headerDesc;
        private final CacheProvider cacheProvider;
        private final ServerUserLogonSessionFactory serverUserLogonSessionFactory;

        public MyPerTargetBuilder(DeserializerFixReaderBuilder deserializerFixReaderBuilder,
                                  SerializerFixWriterBuilder serializerFixWriterBuilder, HeaderDesc headerDesc, CacheProvider cacheProvider, ServerUserLogonSessionFactory serverUserLogonSessionFactory) {
            this.deserializerFixReaderBuilder = deserializerFixReaderBuilder;
            this.serializerFixWriterBuilder = serializerFixWriterBuilder;
            this.headerDesc = headerDesc;
            this.cacheProvider = cacheProvider;
            this.serverUserLogonSessionFactory = serverUserLogonSessionFactory;
        }

        @Override
        public NewAcceptorFixConnectionImpl.PerTarget create(String senderCompID, String targetCompID, Publish publish,
                                                             ByteReader byteReader, byte[] initialBuffer, int len) {
            final CacheProvider.SeqNumAndCache cachedData = cacheProvider.getCachedData();
            final FixReader reader = deserializerFixReaderBuilder.createReader(byteReader, initialBuffer, len);
            return new NewAcceptorFixConnectionImpl.PerTarget(cachedData.cachedData(), reader,
                    serializerFixWriterBuilder.createWriter(publish, cachedData.msgSeqProvider()), headerDesc, serverUserLogonSessionFactory);
        }
    }
}