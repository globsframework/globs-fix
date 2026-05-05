package org.globsframework.fix.engine;

import org.globsframework.core.metamodel.GlobModel;
import org.globsframework.core.metamodel.impl.DefaultGlobModel;
import org.globsframework.core.model.Glob;
import org.globsframework.core.model.MutableGlob;
import org.globsframework.core.utils.Ref;
import org.globsframework.fix.HeaderType;
import org.globsframework.fix.TrailerType;
import org.globsframework.fix.deserializer.DeserializerFixReaderBuilder;
import org.globsframework.fix.deserializer.FixMessageValue;
import org.globsframework.fix.dictionary.FixModel;
import org.globsframework.fix.dictionary.admin.LogonType;
import org.globsframework.fix.dictionary.xml.FieldFactoryImpl;
import org.globsframework.fix.dictionary.xml.ReadFixDictionary;
import org.globsframework.fix.fix44.app.QuoteRequestType;
import org.globsframework.fix.fix44.app.QuoteResponseType;
import org.globsframework.fix.serializer.FixWriter;
import org.globsframework.fix.serializer.Publish;
import org.globsframework.fix.serializer.SerializerFixWriterBuilder;
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
import java.util.List;
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

        Ref<CompletableFuture<ClientLogonSession>> testUserClientLogonSessionRef = new Ref<>();
        final ClientUserLogonSessionFactory userLogonSessionFactory = new ClientUserLogonSessionFactory(clientLogonSession -> {
            testUserClientLogonSessionRef.get().complete(clientLogonSession);
        });
        final NoCacheDataAdapt initiatorDataAdapt = new NoCacheDataAdapt();
        ClientSeqMsgId clientSeqMsgId = initiatorDataAdapt.clientSeqMsgId();

        fixClient = createFixClient(port, userLogonSessionFactory, initiatorDataAdapt);

        final PriceListenerImpl priceListener = new PriceListenerImpl();
        {
            testUserClientLogonSessionRef.set(new CompletableFuture<>());
            fixClient.connect();

            final ClientLogonSession clientLogonSession = testUserClientLogonSessionRef.get().get(10, TimeUnit.MILLISECONDS);

            priceListener.actualPrices = new CompletableFuture<>();
            clientLogonSession.subscribe("EUR", priceListener);
            final List<String> list = priceListener.actualPrices.get(100, TimeUnit.SECONDS);
            System.out.println("Prices: " + list);
            Assertions.assertEquals(3, list.size());

            fixClient.disconnect().join();
        }
        priceListener.actualPrices = new CompletableFuture<>();
        {
            testUserClientLogonSessionRef.set(new CompletableFuture<>());
            fixClient.connect();

            final ClientLogonSession clientLogonSession = testUserClientLogonSessionRef.get().get(10, TimeUnit.MILLISECONDS);

            priceListener.actualPrices = new CompletableFuture<>();
            clientLogonSession.subscribe("EUR", priceListener);
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

            final ClientLogonSession clientLogonSession = testUserClientLogonSessionRef.get().get(10, TimeUnit.MILLISECONDS);

            priceListener.actualPrices = new CompletableFuture<>();
            clientLogonSession.subscribe("EUR", priceListener);
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

            final ClientLogonSession clientLogonSession = testUserClientLogonSessionRef.get().get(10, TimeUnit.MILLISECONDS);

            priceListener.actualPrices = new CompletableFuture<>();
            clientLogonSession.subscribe("EUR", priceListener);
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

            final ClientLogonSession clientLogonSession = testUserClientLogonSessionRef.get().get(10, TimeUnit.MILLISECONDS);

            priceListener.actualPrices = new CompletableFuture<>();
            clientLogonSession.subscribe("EUR", priceListener);
            final List<String> list = priceListener.actualPrices.get(100, TimeUnit.SECONDS);
            System.out.println("Prices: " + list);
            Assertions.assertEquals(3, list.size());

            fixClient.disconnect().join();
        }
        fixClient = null;
    }

    @Test
    void testWithSave() throws Exception {

        final FixInfoProvider.DataAdapt acceptorDataAdapt = InMemoryCacheDataAdapt.create(10, headerDesc.seqNumField());
        ClientSeqMsgId serverSeqMsgId = acceptorDataAdapt.clientSeqMsgId();

        fixServer = createFixServer(acceptorDataAdapt);

        executorService.submit(fixServer::processConnections);

        final int port = fixServer.getPort();

        Ref<CompletableFuture<ClientLogonSession>> testUserClientLogonSessionRef = new Ref<>();
        final ClientUserLogonSessionFactory userLogonSessionFactory = new ClientUserLogonSessionFactory(clientLogonSession -> {
            testUserClientLogonSessionRef.get().complete(clientLogonSession);
        });
        final NoCacheDataAdapt initiatorDataAdapt = new NoCacheDataAdapt();
        ClientSeqMsgId clientSeqMsgId = initiatorDataAdapt.clientSeqMsgId();

        fixClient = createFixClient(port, userLogonSessionFactory, initiatorDataAdapt);

        final PriceListenerImpl priceListener = new PriceListenerImpl();
        {
            testUserClientLogonSessionRef.set(new CompletableFuture<>());
            fixClient.connect();

            final ClientLogonSession clientLogonSession = testUserClientLogonSessionRef.get().get(10, TimeUnit.MILLISECONDS);

            priceListener.actualPrices = new CompletableFuture<>();
            clientLogonSession.subscribe("EUR", priceListener);
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

            final ClientLogonSession clientLogonSession = testUserClientLogonSessionRef.get().get(10, TimeUnit.MILLISECONDS);
            Assertions.assertEquals(3, clientLogonSession.checkReceived(3));
            priceListener.actualPrices = new CompletableFuture<>();
            clientLogonSession.subscribe("EUR", priceListener);
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
                        new ServerUserLogonSessionFactory(scheduledExecutorService, 3, 100));
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
        private final int maxElementToSend;
        private final long delayInMs;

        public PricerImpl(ScheduledExecutorService executorService, int maxElementToSend,
                          long delayInMs) {
            this.executorService = executorService;
            this.maxElementToSend = maxElementToSend;
            this.delayInMs = delayInMs;
        }

        @Override
        public void subscribe(String symbol, Price price) {
            AtomicInteger count = new AtomicInteger(0);
            executorService.scheduleAtFixedRate(() -> {
                price.price(String.valueOf(System.currentTimeMillis()));
                if (count.incrementAndGet() == maxElementToSend) {
                    throw new RuntimeException("Stop");
                }
            }, 0, delayInMs, TimeUnit.MILLISECONDS);
        }
    }


    public static class LoggerPublish implements Publish {
        @Override
        public void publish(byte[] data, int offset, int length) {
//            log.info("Publishing " + new String(data, offset, length));
        }
    }

    //------------------------- Acceptor side


    private static class PriceListenerImpl implements ClientLogonSession.PriceListener {
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