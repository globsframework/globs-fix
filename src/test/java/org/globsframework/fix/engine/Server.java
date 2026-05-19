package org.globsframework.fix.engine;

import org.globsframework.core.metamodel.GlobModel;
import org.globsframework.core.metamodel.impl.DefaultGlobModel;
import org.globsframework.fix.HeaderType;
import org.globsframework.fix.TrailerType;
import org.globsframework.fix.UTCFormater;
import org.globsframework.fix.deserializer.DeserializerFixReaderBuilder;
import org.globsframework.fix.dictionary.FixModel;
import org.globsframework.fix.dictionary.xml.FieldFactoryImpl;
import org.globsframework.fix.dictionary.xml.ReadFixDictionary;
import org.globsframework.fix.fix44.app.ExecutionReportType;
import org.globsframework.fix.fix44.app.NewOrderSingleType;
import org.globsframework.fix.fix44.app.QuoteRequestType;
import org.globsframework.fix.fix44.app.QuoteResponseType;
import org.globsframework.fix.serializer.SerializerFixWriterBuilder;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

public class Server {
    public static void main(String[] args) throws Exception {
        final FixModel fixModel = ReadFixDictionary.parse("fix44", () ->
                new InputStreamReader(FixServer.class.getClassLoader().getResourceAsStream("FIX44.xml"),
                        StandardCharsets.UTF_8), new FieldFactoryImpl());
        final ExecutorService executorService = Executors.newCachedThreadPool();
        final ScheduledExecutorService scheduledExecutorService = Executors.newSingleThreadScheduledExecutor();

        final GlobModel globModel = new DefaultGlobModel(QuoteRequestType.TYPE, QuoteResponseType.TYPE,
                NewOrderSingleType.TYPE, ExecutionReportType.TYPE);

        final DeserializerFixReaderBuilder deserializerFixReaderBuilder = DeserializerFixReaderBuilder.create(fixModel, globModel, HeaderType.TYPE, TrailerType.TYPE);
        final SerializerFixWriterBuilder serializerFixWriterBuilder = SerializerFixWriterBuilder.create(fixModel, globModel, HeaderType.TYPE, TrailerType.TYPE,
                UTCFormater.withAutoRefresh(scheduledExecutorService));
        final HeaderDesc headerDesc = HeaderDesc.create(HeaderType.TYPE);

        final SingleSerializerProvider serializerProvider =
                new SingleSerializerProvider(deserializerFixReaderBuilder, serializerFixWriterBuilder, headerDesc);

        final FixServer fixServer = new FixServer("0.0.0.0", 5456,
                new FixConnectionFactory(new FixServerTest.LoggerPublish(), executorService, scheduledExecutorService,
                        new ServerUserLogonSessionFactory(scheduledExecutorService, 1000, 1),
                        (String senderCompID, String targetCompID) -> new NoCacheDataAdapt(), serializerProvider, headerDesc));

        executorService.submit(fixServer::acceptAsAcceptor);

        Thread.currentThread().join();
    }
}
