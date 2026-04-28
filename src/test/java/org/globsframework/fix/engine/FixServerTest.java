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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
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
    private FixModel fixModel;
    private GlobModel globModel;
    private DeserializerFixReaderBuilder deserializerFixReaderBuilder;
    private SerializerFixWriterBuilder serializerFixWriterBuilder;
    private HeaderDesc headerDesc;
    private ExecutorService executorService;
    private ScheduledExecutorService scheduledExecutorService;
    private SingleSerializerProvider serializerProvider;
    private FixServer fixServer;
    private FixClient fixClient;


    @BeforeEach
    void setUp() throws IOException {
        fixModel = ReadFixDictionary.parse("fix44", () ->
                new InputStreamReader(getClass().getClassLoader().getResourceAsStream("FIX44.xml"),
                        StandardCharsets.UTF_8), new FieldFactoryImpl());

        globModel = new DefaultGlobModel(QuoteRequestType.TYPE, QuoteResponseType.TYPE);

        deserializerFixReaderBuilder = DeserializerFixReaderBuilder.create(fixModel, globModel, HeaderType.TYPE, TrailerType.TYPE);
        serializerFixWriterBuilder = SerializerFixWriterBuilder.create(fixModel, globModel, HeaderType.TYPE, TrailerType.TYPE);
        headerDesc = HeaderDesc.create(HeaderType.TYPE);

        executorService = Executors.newCachedThreadPool();
        scheduledExecutorService = Executors.newSingleThreadScheduledExecutor();
//        final BasicMsgSeqProvider serverMsgSeqProvider = new BasicMsgSeqProvider();
        serializerProvider = new SingleSerializerProvider(deserializerFixReaderBuilder, serializerFixWriterBuilder, headerDesc);
    }

    @AfterEach
    void tearDown() throws ExecutionException, InterruptedException, TimeoutException {
//        if (fixClient != null) {
//            fixClient.disconnect();
//        }
//        if (fixServer != null) {
//            fixServer.shutdown();
//        }
    }

    @Test
    void clientServer() throws IOException, ExecutionException, InterruptedException, TimeoutException {

        final NoCacheDataAdapt acceptorDataAdapt = new NoCacheDataAdapt();
        ClientSeqMsgId serverSeqMsgId = acceptorDataAdapt.clientSeqMsgId();

        fixServer = createFixServer(acceptorDataAdapt);

        executorService.submit(fixServer::processConnections);

        final int port = fixServer.getPort();

        Ref<CompletableFuture<TestUserClientLogonSession>> testUserClientLogonSessionRef = new Ref<>();
        final ClientUserLogonSessionFactory userLogonSessionFactory = new ClientUserLogonSessionFactory(testUserClientLogonSession -> {
            testUserClientLogonSessionRef.get().complete(testUserClientLogonSession);
        });
        final NoCacheDataAdapt initiatorDataAdapt = new NoCacheDataAdapt();
        ClientSeqMsgId clientSeqMsgId = initiatorDataAdapt.clientSeqMsgId();

        fixClient = createFixClient(port, userLogonSessionFactory, initiatorDataAdapt);

        final PriceListenerImpl priceListener = new PriceListenerImpl();
        {
            testUserClientLogonSessionRef.set(new CompletableFuture<>());
            fixClient.connect();

            final TestUserClientLogonSession testUserClientLogonSession = testUserClientLogonSessionRef.get().get(10, TimeUnit.MILLISECONDS);

            priceListener.actualPrices = new CompletableFuture<>();
            testUserClientLogonSession.subscribe("EUR", priceListener);
            final List<String> list = priceListener.actualPrices.get(100, TimeUnit.SECONDS);
            System.out.println("Prices: " + list);
            Assertions.assertEquals(3, list.size());

            fixClient.disconnect().join();
        }
        priceListener.actualPrices = new CompletableFuture<>();
        {
            testUserClientLogonSessionRef.set(new CompletableFuture<>());
            fixClient.connect();

            final TestUserClientLogonSession testUserClientLogonSession = testUserClientLogonSessionRef.get().get(10, TimeUnit.MILLISECONDS);

            priceListener.actualPrices = new CompletableFuture<>();
            testUserClientLogonSession.subscribe("EUR", priceListener);
            final List<String> list = priceListener.actualPrices.get(100, TimeUnit.SECONDS);
            System.out.println("Prices: " + list);
            Assertions.assertEquals(3, list.size());

            fixClient.disconnect().join();
        }

        System.out.println("test GAP in client");
        priceListener.actualPrices = new CompletableFuture<>();
        clientSeqMsgId.reset(clientSeqMsgId.current() - 4);
        {
            testUserClientLogonSessionRef.set(new CompletableFuture<>());
            fixClient.connect();

            final TestUserClientLogonSession testUserClientLogonSession = testUserClientLogonSessionRef.get().get(10, TimeUnit.MILLISECONDS);

            priceListener.actualPrices = new CompletableFuture<>();
            testUserClientLogonSession.subscribe("EUR", priceListener);
            final List<String> list = priceListener.actualPrices.get(100, TimeUnit.SECONDS);
            System.out.println("Prices: " + list);
            Assertions.assertEquals(3, list.size());

            fixClient.disconnect().join();
        }

        System.out.println("test GAP on server side");
        priceListener.actualPrices = new CompletableFuture<>();
        serverSeqMsgId.reset(serverSeqMsgId.current() - 4);
        {
            testUserClientLogonSessionRef.set(new CompletableFuture<>());
            fixClient.connect();

            final TestUserClientLogonSession testUserClientLogonSession = testUserClientLogonSessionRef.get().get(10, TimeUnit.MILLISECONDS);

            priceListener.actualPrices = new CompletableFuture<>();
            testUserClientLogonSession.subscribe("EUR", priceListener);
            final List<String> list = priceListener.actualPrices.get(100, TimeUnit.SECONDS);
            System.out.println("Prices: " + list);
            Assertions.assertEquals(3, list.size());

            fixClient.disconnect().join();
        }

        System.out.println("test GAP on server and client side");
        priceListener.actualPrices = new CompletableFuture<>();
        serverSeqMsgId.reset(serverSeqMsgId.current() - 4);
        clientSeqMsgId.reset(clientSeqMsgId.current() - 4);
        {
            testUserClientLogonSessionRef.set(new CompletableFuture<>());
            fixClient.connect();

            final TestUserClientLogonSession testUserClientLogonSession = testUserClientLogonSessionRef.get().get(10, TimeUnit.MILLISECONDS);

            priceListener.actualPrices = new CompletableFuture<>();
            testUserClientLogonSession.subscribe("EUR", priceListener);
            final List<String> list = priceListener.actualPrices.get(100, TimeUnit.SECONDS);
            System.out.println("Prices: " + list);
            Assertions.assertEquals(3, list.size());

            fixClient.disconnect().join();
        }
        fixClient = null;
    }

    @Test
    void testWithSave() throws Exception {

        final FixInfoProvider.DataAdapt acceptorDataAdapt =  InMemoryCacheDataAdapt.create(10, headerDesc.seqNumField());
        ClientSeqMsgId serverSeqMsgId = acceptorDataAdapt.clientSeqMsgId();

        fixServer = createFixServer(acceptorDataAdapt);

        executorService.submit(fixServer::processConnections);

        final int port = fixServer.getPort();

        Ref<CompletableFuture<TestUserClientLogonSession>> testUserClientLogonSessionRef = new Ref<>();
        final ClientUserLogonSessionFactory userLogonSessionFactory = new ClientUserLogonSessionFactory(testUserClientLogonSession -> {
            testUserClientLogonSessionRef.get().complete(testUserClientLogonSession);
        });
        final NoCacheDataAdapt initiatorDataAdapt = new NoCacheDataAdapt();
        ClientSeqMsgId clientSeqMsgId = initiatorDataAdapt.clientSeqMsgId();

        fixClient = createFixClient(port, userLogonSessionFactory, initiatorDataAdapt);

        final PriceListenerImpl priceListener = new PriceListenerImpl();
        {
            testUserClientLogonSessionRef.set(new CompletableFuture<>());
            fixClient.connect();

            final TestUserClientLogonSession testUserClientLogonSession = testUserClientLogonSessionRef.get().get(10, TimeUnit.MILLISECONDS);

            priceListener.actualPrices = new CompletableFuture<>();
            testUserClientLogonSession.subscribe("EUR", priceListener);
            final List<String> list = priceListener.actualPrices.get(100, TimeUnit.SECONDS);
            System.out.println("Prices: " + list);
            Assertions.assertEquals(3, list.size());

            fixClient.disconnect().join();
        }

        serverSeqMsgId.reset(serverSeqMsgId.current() - 2);
        priceListener.actualPrices = new CompletableFuture<>();
        {
            testUserClientLogonSessionRef.set(new CompletableFuture<>());
            fixClient.connect();

            final TestUserClientLogonSession testUserClientLogonSession = testUserClientLogonSessionRef.get().get(10, TimeUnit.MILLISECONDS);
            Assertions.assertEquals(3, testUserClientLogonSession.checkReceived(3));
            priceListener.actualPrices = new CompletableFuture<>();
            testUserClientLogonSession.subscribe("EUR", priceListener);
            {
                final List<String> list = priceListener.actualPrices.get(1000, TimeUnit.SECONDS);
                System.out.println("Prices: " + list);
                Assertions.assertEquals(3, list.size());
            }

            fixClient.disconnect().join();
        }
        fixClient = null;
    }

    private FixServer createFixServer(FixInfoProvider.DataAdapt acceptorDataAdapt) throws IOException {
        final NewAcceptorFixConnectionImpl acceptorFixConnection =
                new NewAcceptorFixConnectionImpl(executorService, scheduledExecutorService, 49, 56, (byte) 0x1,
                        serializerProvider,
                                (String senderCompID, String targetCompID) -> {
                                    return acceptorDataAdapt;
                                },
                                new ServerUserLogonSessionFactory(scheduledExecutorService));
        return new FixServer("0.0.0.0", 0, new FixConnectionFactory(acceptorFixConnection, new LoggerPublish()));
    }

    private FixClient createFixClient(int port, ClientUserLogonSessionFactory userLogonSessionFactory, NoCacheDataAdapt initiatorDataAdapt) {
        return new FixClient("localhost", port,
                new FixConnectionFactory(
                        new NewInitiatorFixConnectionImpl(executorService, scheduledExecutorService, userLogonSessionFactory,
                                (String senderCompID, String targetCompID) -> initiatorDataAdapt,
                                serializerProvider,
                                HeaderDesc.create(HeaderType.TYPE)
                        ),
                        new LoggerPublish()));
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
        public UserLogonSession create(Shutdown shutdown) {
            TestUserClientLogonSession testUserClientLogonSession =
                    new TestUserClientLogonSession(shutdown, "AF", "BNP");
            notifyNewClient.newClient(testUserClientLogonSession);
            return testUserClientLogonSession;
        }
    }


    public static class TestUserClientLogonSession implements UserLogonSession, Connected {
        private final Shutdown shutdown;
        private final String senderCompID;
        private final String targetCompoID;
        private final Map<String, PriceListener> listeners = new HashMap<>();
        private final List<FixMessageValue> received = new ArrayList<>();

        public TestUserClientLogonSession(Shutdown shutdown,
                                          String senderCompID, String targetCompoID) {
            this.shutdown = shutdown;
            this.senderCompID = senderCompID;
            this.targetCompoID = targetCompoID;
        }

        @Override
        public UserSession initiator() {
            return new ClientUserSession(this);
        }

        @Override
        public UserSession acceptor(String senderCompID, String targetCompID) {
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

        public int checkReceived(int count) throws InterruptedException {
            synchronized (received) {
                long end = System.currentTimeMillis() + 1000;
                while (count < received.size() && end > System.currentTimeMillis()) {
                    received.wait();
                }
            }
            return count;
        }

        private class ClientUserSession implements UserSession {
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
            public void logonFail() {
            }

            @Override
            public Glob getHeader() {
                return HeaderType.create(senderCompID, targetCompoID);
            }

            @Override
            public AppMessageReceiver connected(FixMessageValue logon, FixWriter appWriter) {
                synchronized (this) {
                    connected.connected(targetCompoID, this);
                    for (String symbol : listeners.keySet()) {
                        appWriter.write(getHeader().duplicate(), QuoteRequestType.TYPE.instantiate()
                                        .set(QuoteRequestType.quoteReqID, symbol)
                                , null, false);
                    }
                }
                return new AppMessageReceiver() {
                    @Override
                    public void messages(FixMessageValue fixMessageValue) {
                        synchronized (received) {
                            received.add(fixMessageValue);
                            received.notify();
                        }
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
//            log.info("Publishing " + new String(data, offset, length));
        }
    }

    //------------------------- Acceptor side


    public static class ServerUserLogonSessionFactory implements UserLogonSessionFactory {
        private final ScheduledExecutorService scheduledExecutorService;

        public ServerUserLogonSessionFactory(ScheduledExecutorService scheduledExecutorService) {
            this.scheduledExecutorService = scheduledExecutorService;
        }

        @Override
        public UserLogonSession create(Shutdown shutdown) {
            return new TestUserServerLogonSession(shutdown, new PricerImpl(scheduledExecutorService));
        }
    }

    private static class TestUserServerLogonSession implements UserLogonSession {
        private final Shutdown shutdown;
        private final Pricer pricer;

        public TestUserServerLogonSession(Shutdown shutdown, Pricer pricer) {
            this.shutdown = shutdown;
            this.pricer = pricer;
        }

        @Override
        public UserSession initiator() {
            throw new RuntimeException("Pricer side is acceptor");
        }

        @Override
        public UserSession acceptor(String senderCompID, String targetCompID) {
            // accept connection en inverse sender and target
            return new ServerPricerUserSession(targetCompID, senderCompID);
        }

        private class ServerPricerUserSession implements UserSession {

            private final String senderCompID;
            private final String targetCompID;

            public ServerPricerUserSession(String senderCompID, String targetCompID) {
                this.senderCompID = senderCompID;
                this.targetCompID = targetCompID;
            }

            public AppMessageReceiver connected(FixMessageValue logon, FixWriter appWriter) {
                return new AppMessageReceiver() {
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
                                        null, false);
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
            public void logonFail() {
            }

            @Override
            public MutableGlob getHeader() {
                return HeaderType.create(senderCompID, targetCompID);
            }
        }
    }

    private static class PriceListenerImpl implements TestUserClientLogonSession.PriceListener {
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
    }
}