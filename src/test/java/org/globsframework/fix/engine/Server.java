package org.globsframework.fix.engine;

import org.globsframework.core.metamodel.GlobModel;
import org.globsframework.core.metamodel.impl.DefaultGlobModel;
import org.globsframework.fix.HeaderType;
import org.globsframework.fix.TrailerType;
import org.globsframework.fix.deserializer.DeserializerFixReaderBuilder;
import org.globsframework.fix.dictionary.FixModel;
import org.globsframework.fix.dictionary.xml.FieldFactoryImpl;
import org.globsframework.fix.dictionary.xml.ReadFixDictionary;
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

        final GlobModel globModel = new DefaultGlobModel(QuoteRequestType.TYPE, QuoteResponseType.TYPE);

        final DeserializerFixReaderBuilder deserializerFixReaderBuilder = DeserializerFixReaderBuilder.create(fixModel, globModel, HeaderType.TYPE, TrailerType.TYPE);
        final SerializerFixWriterBuilder serializerFixWriterBuilder = SerializerFixWriterBuilder.create(fixModel, globModel, HeaderType.TYPE, TrailerType.TYPE);
        final HeaderDesc headerDesc = HeaderDesc.create(HeaderType.TYPE);

        final DefaultSerializerProvider serializerProvider = new DefaultSerializerProvider(deserializerFixReaderBuilder, serializerFixWriterBuilder, headerDesc);

        final ExecutorService executorService = Executors.newCachedThreadPool();
        final ScheduledExecutorService scheduledExecutorService = Executors.newSingleThreadScheduledExecutor();
        final NewAcceptorFixConnectionImpl acceptorFixConnection =
                new NewAcceptorFixConnectionImpl(executorService, scheduledExecutorService, 49, 56, (byte) 0x1,
                        serializerProvider,
                                (String senderCompID, String targetCompID) -> new NewAcceptorFixConnectionImpl.NoCacheDataAdapt(),
                                new FixServerTest.ServerUserLogonSessionFactory(scheduledExecutorService));
        final FixServer fixServer = new FixServer("0.0.0.0", 5456, new FixConnectionFactory(acceptorFixConnection, new FixServerTest.LoggerPublish()));

        executorService.submit(fixServer::processConnections);

        Thread.currentThread().join();
    }
}
