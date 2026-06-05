package org.globsframework.fix.engine;

import org.globsframework.core.metamodel.GlobModel;
import org.globsframework.core.metamodel.impl.DefaultGlobModel;
import org.globsframework.fix.HeaderType;
import org.globsframework.fix.TrailerType;
import org.globsframework.fix.FormatDateTime;
import org.globsframework.fix.deserializer.ByteReader;
import org.globsframework.fix.deserializer.DeserializerFixReaderBuilder;
import org.globsframework.fix.dictionary.FixModel;
import org.globsframework.fix.dictionary.xml.FieldFactoryImpl;
import org.globsframework.fix.dictionary.xml.ReadFixDictionary;
import org.globsframework.fix.fix44.app.ExecutionReportType;
import org.globsframework.fix.fix44.app.NewOrderSingleType;
import org.globsframework.fix.fix44.app.QuoteRequestType;
import org.globsframework.fix.fix44.app.QuoteResponseType;
import org.globsframework.fix.serializer.Publish;
import org.globsframework.fix.serializer.SerializerFixWriterBuilder;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class DirectClientServer {
    public static void main(String[] args) throws Exception {

        Transfert clientToServer = new Transfert(10);
        Transfert serverToClient = new Transfert(10);
        {
            final FixModel fixModel = ReadFixDictionary.parse("fix44", () ->
                    new InputStreamReader(FixServer.class.getClassLoader().getResourceAsStream("FIX44.xml"),
                            StandardCharsets.UTF_8), new FieldFactoryImpl());
            final ExecutorService executorService = Executors.newCachedThreadPool();
            final ScheduledExecutorService scheduledExecutorService = Executors.newSingleThreadScheduledExecutor();

            final GlobModel globModel = new DefaultGlobModel(QuoteRequestType.TYPE, QuoteResponseType.TYPE,
                    NewOrderSingleType.TYPE, ExecutionReportType.TYPE);

            final DeserializerFixReaderBuilder deserializerFixReaderBuilder = DeserializerFixReaderBuilder.create(fixModel, globModel, HeaderType.TYPE, TrailerType.TYPE);
            final SerializerFixWriterBuilder serializerFixWriterBuilder = SerializerFixWriterBuilder.create(fixModel, globModel, HeaderType.TYPE, TrailerType.TYPE,
                    FormatDateTime.autoRefreshUTC(scheduledExecutorService));
            final HeaderDesc headerDesc = HeaderDesc.create(HeaderType.TYPE);

            final SingleSerializerProvider serializerProvider =
                    new SingleSerializerProvider(deserializerFixReaderBuilder, serializerFixWriterBuilder, headerDesc);

            final FixConnectionFactory fixConnectionFactory = new FixConnectionFactory(new FixServerTest.LoggerPublish(), executorService, scheduledExecutorService,
                    new ServerUserLogonSessionFactory(scheduledExecutorService, 1000, 2),
                    (String senderCompID, String targetCompID) -> new NoCacheDataAdapt(), serializerProvider, headerDesc);

            final NewFixConnection acceptor = fixConnectionFactory.createAcceptor();
            acceptor.onNew(clientToServer, serverToClient, () -> {
            });
        }

        {

            final ExecutorService executorService = Executors.newCachedThreadPool();
            final ScheduledExecutorService scheduledExecutorService = Executors.newSingleThreadScheduledExecutor();

            final FixModel fixModel = ReadFixDictionary.parse("fix44", () ->
                    new InputStreamReader(FixServer.class.getClassLoader().getResourceAsStream("FIX44.xml"),
                            StandardCharsets.UTF_8), new FieldFactoryImpl());

            final GlobModel globModel = new DefaultGlobModel(NewOrderSingleType.TYPE, ExecutionReportType.TYPE);

            final SingleSerializerProvider serializerProvider = new SingleSerializerProvider(
                    DeserializerFixReaderBuilder.create(fixModel, globModel, HeaderType.TYPE, TrailerType.TYPE),
                    SerializerFixWriterBuilder.create(fixModel, globModel, HeaderType.TYPE, TrailerType.TYPE,
                            FormatDateTime.autoRefreshUTC(scheduledExecutorService)),
                    HeaderType.getHeaderDesc());

            final FixConnectionFactory fixConnectionFactory = new FixConnectionFactory(
                    new FixServerTest.LoggerPublish(), executorService, scheduledExecutorService,
                    new UserLogonSessionFactory() {
                        @Override
                        public UserSession create(String senderCompId, String targetCompId, Shutdown shutdown) {
                            return new SingleOrderClient.SingleOrderUserSession(senderCompId, targetCompId, shutdown);
                        }
                    }, (senderCompID, targetCompID) -> new NoCacheDataAdapt(),
                    serializerProvider, HeaderDesc.create(HeaderType.TYPE));

            final NewFixConnection newFixConnection = fixConnectionFactory.createInitiator("AF", "BNP");
            newFixConnection.onNew(serverToClient, clientToServer, () -> {
            });
            Thread.currentThread().join();
            executorService.shutdown();
            scheduledExecutorService.shutdownNow();
            executorService.awaitTermination(100, TimeUnit.SECONDS);
            scheduledExecutorService.awaitTermination(100, TimeUnit.SECONDS);
        }
    }

    private static class Transfert implements Publish, ByteReader {
        private final Container[] data;
        private final int capacity;
        private final int mask;
        volatile long readOffset;
        volatile long writeOffset;

        private Transfert(int minSize) {
            capacity = powerOf2(minSize);
            data = new Container[capacity];
            mask = capacity - 1;
            for (int i = 0; i < data.length; i++) {
                data[i] = new Container(new byte[1024], 0);
            }
        }


        private static int powerOf2(int minSize) {
            int capacity = 1;
            while (capacity < minSize) {
                capacity = capacity << 2;
            }
            return capacity;
        }

        @Override
        public int read(byte[] buf, int offset, int len) {
            while (readOffset >= writeOffset) {
                Thread.yield();
            }
            Container d = this.data[Math.toIntExact(readOffset & mask)];
            int minCopy = Math.min(len, d.len - d.read);
            System.arraycopy(d.data, d.read, buf, offset, minCopy);
            if (d.read + minCopy == d.len) {
                readOffset++;
            }
            else {
                d.read += minCopy;
            }
            return minCopy;
        }

        @Override
        public void publish(byte[] data, int offset, int length) {
            while (true) {
                while (writeOffset - readOffset >= capacity) {
                    Thread.yield();
                }
                final int off = Math.toIntExact(writeOffset & mask);
                Container datum = this.data[off];
                final int maxLen = datum.data.length;
                if (length > maxLen) {
                    System.arraycopy(data, offset, datum.data, 0, maxLen);
                    datum.read = 0;
                    datum.len = maxLen;
                    writeOffset++;
                    offset += maxLen;
                    length -= maxLen;
//                    publish(data, offset + maxLen, length - maxLen);
                } else {
                    System.arraycopy(data, offset, datum.data, 0, length);
                    datum.read = 0;
                    datum.len = length;
                    writeOffset++;
                    return;
                }
            }
        }

        static class Container {
            final byte[] data;
            int len;
            int read;

            public Container(byte[] data, int len) {
                this.data = data;
                this.len = len;
            }
        }
    }
}
