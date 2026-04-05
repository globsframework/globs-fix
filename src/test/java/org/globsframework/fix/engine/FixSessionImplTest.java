package org.globsframework.fix.engine;

import org.globsframework.core.metamodel.GlobModel;
import org.globsframework.core.metamodel.impl.DefaultGlobModel;
import org.globsframework.fix.HeaderType;
import org.globsframework.fix.TrailerType;
import org.globsframework.fix.deserializer.BasicMsgSeqProvider;
import org.globsframework.fix.dictionary.FixModel;
import org.globsframework.fix.dictionary.admin.HeartbeatType;
import org.globsframework.fix.dictionary.admin.LogonType;
import org.globsframework.fix.dictionary.xml.FieldFactoryImpl;
import org.globsframework.fix.dictionary.xml.ReadFixDictionary;
import org.globsframework.fix.fix44.app.QuoteRequestType;
import org.globsframework.fix.serializer.*;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

class FixSessionImplTest {

    ScheduledExecutorService executorService = Executors.newSingleThreadScheduledExecutor();

    @Test
    void nominalLoggon() throws IOException {
        final FixModel fixModel = ReadFixDictionary.parse("fix44", () ->
                new InputStreamReader(getClass().getClassLoader().getResourceAsStream("FIX44.xml"),
                        StandardCharsets.UTF_8), new FieldFactoryImpl());

        final GlobModel globModel = new DefaultGlobModel(HeartbeatType.TYPE, LogonType.TYPE, QuoteRequestType.TYPE);
        final SerializerFixWriterBuilder fixWriterBuilder = SerializerFixWriterBuilder.create(fixModel, globModel, HeaderType.TYPE, TrailerType.TYPE);

        List<byte[]> datas = new ArrayList<>();
        final FixWriter writer = fixWriterBuilder.createWriter(new Publish() {
            @Override
            public void publish(byte[] data, int offset, int length) {
                datas.add(Arrays.copyOfRange(data, offset, offset + length));
            }
        }, new BasicMsgSeqProvider());


//        FixSessionImpl fixSession = new FixSessionImpl(executorService, )
    }
}