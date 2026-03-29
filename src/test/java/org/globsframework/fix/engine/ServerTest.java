package org.globsframework.fix.engine;

import org.globsframework.core.metamodel.GlobModel;
import org.globsframework.core.metamodel.impl.DefaultGlobModel;
import org.globsframework.core.model.Glob;
import org.globsframework.fix.HeaderType;
import org.globsframework.fix.TrailerType;
import org.globsframework.fix.deserializer.FixMessageValue;
import org.globsframework.fix.deserializer.FixReadBuilder;
import org.globsframework.fix.dictionary.FixModel;
import org.globsframework.fix.dictionary.admin.LogonType;
import org.globsframework.fix.dictionary.xml.FieldFactoryImpl;
import org.globsframework.fix.dictionary.xml.ReadFixDictionary;
import org.globsframework.fix.fix44.app.QuoteRequestType;
import org.globsframework.fix.fix44.app.QuoteResponseType;
import org.globsframework.fix.serializer.FixWriter;
import org.globsframework.fix.serializer.FixWriterBuilder;
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

class ServerTest {

    private static final Logger log = LoggerFactory.getLogger(ServerTest.class);

    @Test
    void clientServer() throws IOException, ExecutionException, InterruptedException, TimeoutException {
        final FixModel fixModel = ReadFixDictionary.parse("fix44", () ->
                new InputStreamReader(getClass().getClassLoader().getResourceAsStream("FIX44.xml"),
                        StandardCharsets.UTF_8), new FieldFactoryImpl());

        final GlobModel globModel = new DefaultGlobModel(QuoteRequestType.TYPE, QuoteResponseType.TYPE);

        final ExecutorService executorService = Executors.newCachedThreadPool();
        final ScheduledExecutorService scheduledExecutorService = Executors.newSingleThreadScheduledExecutor();
        final Server server = new Server("0.0.0.0", 0,
                new FixConnectionFactory(FixReadBuilder.create(fixModel, globModel, HeaderType.TYPE, TrailerType.TYPE),
                        FixWriterBuilder.create(fixModel, globModel, HeaderType.TYPE, TrailerType.TYPE),
                        new NewFixConnectionImpl(executorService, scheduledExecutorService,
                                new ServerUserLogonSessionFactory(scheduledExecutorService))
                ));

        executorService.submit(server::processConnections);

        final int port = server.getPort();

        CompletableFuture<TestUserClientLogonSession> completableFuture = new CompletableFuture<>();
        final ClientUserLogonSessionFactory userLogonSessionFactory = new ClientUserLogonSessionFactory(completableFuture::complete);
        final Client client = new Client("localhost", port, new FixConnectionFactory(FixReadBuilder.create(fixModel, globModel, HeaderType.TYPE, TrailerType.TYPE),
                FixWriterBuilder.create(fixModel, globModel, HeaderType.TYPE, TrailerType.TYPE),
                new NewFixConnectionImpl(executorService, scheduledExecutorService,
                        userLogonSessionFactory)));

        client.connect();

        final TestUserClientLogonSession testUserClientLogonSession = completableFuture.get(10, TimeUnit.MILLISECONDS);

        CompletableFuture<List<String>> actualPrices = new CompletableFuture<>();
        testUserClientLogonSession.subscribe("EUR", new TestUserClientLogonSession.PriceListener() {
            List<String> prices = new ArrayList<>();

            @Override
            public void priceChanged(String str, String p) {
                log.info("EUR price changed: " + str + " " + p);
                prices.add(p);
                if (prices.size() > 10) {
                    actualPrices.complete(prices);
                }
            }
        });

        final List<String> list = actualPrices.get(100, TimeUnit.SECONDS);
        System.out.println("Prices: " + list);

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
            final ScheduledFuture<?> scheduledFuture = executorService.scheduleAtFixedRate(() ->
                    price.price(String.valueOf(System.currentTimeMillis())), 0, 100, TimeUnit.MILLISECONDS);
            executorService.schedule(() -> scheduledFuture.cancel(true), 2, TimeUnit.SECONDS);
        }
    }

    private static class TestUserServerLogonSession implements FixSessionImpl.UserLogonSession, FixSessionImpl.UserSession {
        private final FixWriter writer;
        private final Pricer pricer;

        public TestUserServerLogonSession(FixWriter writer, Shutdown shutdown, Pricer pricer) {
            this.writer = writer;
            this.pricer = pricer;
        }

        @Override
        public Glob sendLogon(FixWriter writer) {
            final Glob loggon = LogonType.create(1);
            writer.write(HeaderType.create("senderSv", "targetSv"),
                    loggon,
                    TrailerType.create());
            return loggon;
        }

        @Override
        public FixSessionImpl.UserSession receiveLogon(FixMessageValue fixMessageValue) {
            return this;
        }

        @Override
        public void messages(FixMessageValue fixMessageValue) {
            final Glob message = fixMessageValue.message();
            if (message != null) {
                if (message.getType() == QuoteRequestType.TYPE) {
                    final String quoteReqId = message.get(QuoteRequestType.quoteReqID);
                    pricer.subscribe(quoteReqId, value -> {
                        writer.write(null,
                                QuoteResponseType.TYPE.instantiate()
                                        .set(QuoteResponseType.quoteRespID, quoteReqId)
                                        .set(QuoteResponseType.bidPx, value),
                                null);
                    });
                }
            }
        }

        @Override
        public void logout(FixMessageValue fixMessageValue) {

        }
    }

    private static class TestUserClientLogonSession implements FixSessionImpl.UserLogonSession, FixSessionImpl.UserSession {
        private final FixWriter writer;
        private final Shutdown shutdown;
        private final Map<String, PriceListener> listeners = new HashMap<>();

        public TestUserClientLogonSession(FixWriter writer, Shutdown shutdown) {
            this.writer = writer;
            this.shutdown = shutdown;
        }

        @Override
        public Glob sendLogon(FixWriter writer) {
            final Glob loggon = LogonType.create(1);
            writer.write(HeaderType.create("senderCl", "targetCl"),
                    loggon,
                    TrailerType.create());
            return loggon;
        }

        @Override
        public FixSessionImpl.UserSession receiveLogon(FixMessageValue fixMessageValue) {
            return this;
        }

        interface PriceListener {
            void priceChanged(String symbol, String bidPx);
        }

        public void subscribe(String symbol, PriceListener priceListener) {
            listeners.put(symbol, priceListener);
            writer.write(null, QuoteRequestType.TYPE.instantiate()
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
        public void logout(FixMessageValue fixMessageValue) {

        }
    }

    private static class ServerUserLogonSessionFactory implements UserLogonSessionFactory {
        private final ScheduledExecutorService scheduledExecutorService;

        public ServerUserLogonSessionFactory(ScheduledExecutorService scheduledExecutorService) {
            this.scheduledExecutorService = scheduledExecutorService;
        }

        @Override
        public FixSessionImpl.UserLogonSession create(FixWriter writer, Shutdown shutdown) {
            return new TestUserServerLogonSession(writer, shutdown, new PricerImpl(scheduledExecutorService));
        }
    }

    private static class ClientUserLogonSessionFactory implements UserLogonSessionFactory {
        private final NotifyNewClient notifyNewClient;

        interface NotifyNewClient {
            void newClient(TestUserClientLogonSession testUserClientLogonSession);
        }

        ClientUserLogonSessionFactory(NotifyNewClient notifyNewClient) {
            this.notifyNewClient = notifyNewClient;
        }

        @Override
        public FixSessionImpl.UserLogonSession create(FixWriter writer, Shutdown shutdown) {
            TestUserClientLogonSession testUserClientLogonSession = new TestUserClientLogonSession(writer, shutdown);
            notifyNewClient.newClient(testUserClientLogonSession);
            return testUserClientLogonSession;
        }
    }
}