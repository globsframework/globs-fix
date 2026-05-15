package org.globsframework.fix.engine;

import com.codahale.metrics.Histogram;
import com.codahale.metrics.Snapshot;
import com.codahale.metrics.UniformReservoir;
import org.globsframework.core.metamodel.GlobModel;
import org.globsframework.core.metamodel.impl.DefaultGlobModel;
import org.globsframework.core.model.Glob;
import org.globsframework.core.model.MutableGlob;
import org.globsframework.fix.HeaderType;
import org.globsframework.fix.TrailerType;
import org.globsframework.fix.UTCFormater;
import org.globsframework.fix.deserializer.DeserializerFixReaderBuilder;
import org.globsframework.fix.deserializer.FixMessageValue;
import org.globsframework.fix.dictionary.FixModel;
import org.globsframework.fix.dictionary.admin.LogonType;
import org.globsframework.fix.dictionary.xml.FieldFactoryImpl;
import org.globsframework.fix.dictionary.xml.ReadFixDictionary;
import org.globsframework.fix.fix44.app.ExecutionReportType;
import org.globsframework.fix.fix44.app.NewOrderSingleType;
import org.globsframework.fix.fix44.app.QuoteRequestType;
import org.globsframework.fix.fix44.app.QuoteResponseType;
import org.globsframework.fix.serializer.FixWriter;
import org.globsframework.fix.serializer.SerializerFixWriterBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

public class SingleOrderClient {
    private static final Logger log = LoggerFactory.getLogger(SingleOrderClient.class);

    public static void main(String[] args) throws Exception {

        final ExecutorService executorService = Executors.newCachedThreadPool();
        final ScheduledExecutorService scheduledExecutorService = Executors.newSingleThreadScheduledExecutor();

        final FixModel fixModel = ReadFixDictionary.parse("fix44", () ->
                new InputStreamReader(FixServer.class.getClassLoader().getResourceAsStream("FIX44.xml"),
                        StandardCharsets.UTF_8), new FieldFactoryImpl());

        final GlobModel globModel = new DefaultGlobModel(NewOrderSingleType.TYPE, ExecutionReportType.TYPE);

        final SingleSerializerProvider serializerProvider = new SingleSerializerProvider(
                DeserializerFixReaderBuilder.create(fixModel, globModel, HeaderType.TYPE, TrailerType.TYPE),
                SerializerFixWriterBuilder.create(fixModel, globModel, HeaderType.TYPE, TrailerType.TYPE,
                        UTCFormater.withAutoRefresh(scheduledExecutorService)),
                HeaderType.getHeaderDesc());

        final FixClient fixClient = new FixClient("localhost", 5456,
                new FixConnectionFactory(
                        new FixServerTest.LoggerPublish(), executorService, scheduledExecutorService,
                        new UserLogonSessionFactory() {
                            @Override
                            public UserSession create(String senderCompId, String targetCompId, Shutdown shutdown) {
                                return new SingleOrderUserSession(senderCompId, targetCompId, shutdown);
                            }
                        }, (senderCompID, targetCompID) -> new NoCacheDataAdapt(),
                        serializerProvider, HeaderDesc.create(HeaderType.TYPE)));

        final CompletableFuture<FixLogout> fixLogoutCompletableFuture = fixClient.connectAsInitiator("AF", "BNP");
        final FixLogout fixLogout = fixLogoutCompletableFuture.join();
        Thread.currentThread().join();
        executorService.shutdown();
        scheduledExecutorService.shutdownNow(); 
        executorService.awaitTermination(100, TimeUnit.SECONDS);
        scheduledExecutorService.awaitTermination(100, TimeUnit.SECONDS);
    }

    private static class SingleOrderUserSession implements UserSession, AppMessageReceiver {
        private final String senderCompId;
        private final String targetCompId;
        private final Shutdown shutdown;
        private final MutableGlob header;
        ExecutorService executorService = Executors.newSingleThreadScheduledExecutor();
        private FixWriter appWriter;
        private final Map<String, Long> sentAt = new ConcurrentHashMap<>();
        private Histogram histogram;


        public SingleOrderUserSession(String senderCompId, String targetCompId, Shutdown shutdown) {
            this.senderCompId = senderCompId;
            this.targetCompId = targetCompId;
            this.shutdown = shutdown;
            header = HeaderType.create(senderCompId, targetCompId);
            histogram = new Histogram(new UniformReservoir());
        }

        @Override
        public void logonFail() {
        }

        @Override
        public Glob getHeader() {
            return header;
        }

        @Override
        public Glob getLogon() {
            return LogonType.create(10);
        }

        @Override
        public AppMessageReceiver connected(FixMessageValue logon, FixWriter appWriter) {
            this.appWriter = appWriter;
            executorService.execute(() -> {
                try {
                    for (int i = 0; i < 10; i++) {
                        FixMessage fixMessage = FixMessageImpl.fromType(header, NewOrderSingleType.TYPE, null);
                        final String orderId = "WARM_" + i;
                        fixMessage.update(NewOrderSingleType.clOrdID, orderId);
                        fixMessage.update(NewOrderSingleType.symbol, "EUR/USD");
                        appWriter.write(fixMessage);
                        Thread.sleep(1);
                    }

                    for (int i = 0; i < 10; i++) {
                        FixMessage fixMessage = FixMessageImpl.fromType(header, NewOrderSingleType.TYPE, null);
                        final String orderId = "TEST_" + i;
                        fixMessage.update(NewOrderSingleType.clOrdID, orderId);
                        fixMessage.update(NewOrderSingleType.symbol, "EUR/USD");
                        sentAt.put(orderId, System.nanoTime());
                        appWriter.write(fixMessage);
                        Thread.sleep(1);
                    }
                    final Snapshot snapshot = histogram.getSnapshot();
                    log.warn("99.9 " + snapshot.get999thPercentile());
                    log.warn("99 " + snapshot.get99thPercentile());
                    log.warn("95 " + snapshot.get95thPercentile());
                    log.warn("75 " + snapshot.get75thPercentile());
                    log.warn("mean " + snapshot.getMean());
                    log.warn("median " + snapshot.getMedian());
                    log.warn("min " + snapshot.getMin());
                    log.warn("max " + snapshot.getMax());
                } catch (Exception e) {
                    log.error("loop break " + e.getMessage(), e);
                }
            });
            return this;
        }

        @Override
        public CompletableFuture<Void> logout() {
            return null;
        }

        @Override
        public void messages(FixMessageValue fixMessageValue) {
            if (fixMessageValue.message().getType() == ExecutionReportType.TYPE) {
                long end = System.nanoTime();
                final String orderID = fixMessageValue.message().get(ExecutionReportType.clOrdID);
                final Long l = sentAt.get(orderID);
                if (l != null) {
                    histogram.update(TimeUnit.NANOSECONDS.toMicros(end - l));
                }
            }
        }
    }
}
