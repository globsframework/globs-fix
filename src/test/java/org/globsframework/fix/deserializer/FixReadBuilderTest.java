package org.globsframework.fix.deserializer;

import org.globsframework.core.metamodel.GlobModel;
import org.globsframework.core.metamodel.GlobType;
import org.globsframework.core.metamodel.GlobTypeBuilder;
import org.globsframework.core.metamodel.GlobTypeBuilderFactory;
import org.globsframework.core.metamodel.fields.StringField;
import org.globsframework.core.metamodel.impl.DefaultGlobModel;
import org.globsframework.core.model.Glob;
import org.globsframework.fix.dictionary.FixModel;
import org.globsframework.fix.dictionary.model.FixFieldType;
import org.globsframework.fix.dictionary.model.FixMessageType;
import org.globsframework.fix.dictionary.xml.FieldFactoryImpl;
import org.globsframework.fix.dictionary.xml.ReadFixDictionary;
import org.globsframework.fix.serializer.FixWriterBuilder;
import org.globsframework.fix.serializer.FixWriterImpl;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class FixReadBuilderTest {

    @Test
    void name() throws IOException {
        final FixModel fixModel = ReadFixDictionary.parse("fix44", () ->
                new InputStreamReader(getClass().getClassLoader().getResourceAsStream("FIX44.xml"),
                        StandardCharsets.UTF_8), new FieldFactoryImpl());

        final GlobModel globModel = new DefaultGlobModel(HeartbeatType.TYPE);
        final FixWriterBuilder fixWriterBuilder = FixWriterBuilder.create(fixModel, globModel, HeaderType.TYPE);

        List<byte[]> datas = new ArrayList<>();
        final FixWriterBuilder.FixWriter writer = fixWriterBuilder.createWriter(new FixWriterImpl.Publish() {
            @Override
            public void publish(byte[] data, int start, int end) {
                datas.add(Arrays.copyOfRange(data, start, end));
            }
        });

        writer.write(HeaderType.create("AA", "BB", "0"), HeartbeatType.create("req"));

        assertEquals(1, datas.size());

        final FixReadBuilder fixReadBuilder = FixReadBuilder.create(fixModel, globModel, HeaderType.TYPE);
        final FixReader reader = fixReadBuilder.createReader(new ByteArrayInputStream(datas.get(0))::read, (byte) 0x1);
        final FixMessageValue read = reader.read();
        assertNotNull(read);
        assertEquals("AA", read.header().get(HeaderType.SenderCompID));
        assertEquals("BB", read.header().get(HeaderType.TargetCompID));
        assertEquals("req", read.message().get(HeartbeatType.TestReqID));
    }

    public static class HeaderType {
        public static final GlobType TYPE;

        public static final StringField SenderCompID;

        public static final StringField TargetCompID;

        public static final StringField MsgType;

        public static Glob create(String aa, String bb, String msgType) {
            return TYPE.instantiate()
                    .set(SenderCompID, aa)
                    .set(TargetCompID, bb)
                    .set(MsgType, msgType)
                    ;
        }

        static {
            final GlobTypeBuilder typeBuilder = GlobTypeBuilderFactory.create("HeaderType");
            SenderCompID = typeBuilder.declareStringField("SenderCompID",
                    FixFieldType.create("SenderCompID"));
            TargetCompID = typeBuilder.declareStringField("TargetCompID", FixFieldType.create("TargetCompID"));
            MsgType = typeBuilder.declareStringField("MsgType", FixFieldType.create("MsgType"));
            TYPE = typeBuilder.build();
        }
    }

    public static class HeartbeatType {
        public static final GlobType TYPE;

        public static final StringField TestReqID;

        static {
            final GlobTypeBuilder typeBuilder = GlobTypeBuilderFactory.create("HeartbeatType");
            typeBuilder.addAnnotation(FixMessageType.create("Heartbeat"));
            TestReqID = typeBuilder.declareStringField("TestReqID", FixFieldType.create("TestReqID"));
            TYPE = typeBuilder.build();
        }

        public static Glob create(String req) {
            return TYPE.instantiate()
                    .set(TestReqID, req);
        }
    }

    public static final String TEST_1 = """
                        """;
}