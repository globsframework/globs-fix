package org.globsframework.fix.deserializer;

import org.globsframework.core.metamodel.GlobModel;
import org.globsframework.core.metamodel.GlobType;
import org.globsframework.core.metamodel.GlobTypeBuilder;
import org.globsframework.core.metamodel.GlobTypeBuilderFactory;
import org.globsframework.core.metamodel.annotations.Target;
import org.globsframework.core.metamodel.fields.GlobArrayField;
import org.globsframework.core.metamodel.fields.GlobField;
import org.globsframework.core.metamodel.fields.StringField;
import org.globsframework.core.metamodel.impl.DefaultGlobModel;
import org.globsframework.core.model.Glob;
import org.globsframework.core.model.MutableGlob;
import org.globsframework.fix.dictionary.FixModel;
import org.globsframework.fix.dictionary.model.FixComponentType;
import org.globsframework.fix.dictionary.model.FixFieldType;
import org.globsframework.fix.dictionary.model.FixGroupType;
import org.globsframework.fix.dictionary.model.FixMessageType;
import org.globsframework.fix.dictionary.xml.FieldFactoryImpl;
import org.globsframework.fix.dictionary.xml.ReadFixDictionary;
import org.globsframework.fix.serializer.FixWriterBuilder;
import org.globsframework.fix.serializer.FixWriterImpl;
import org.junit.jupiter.api.Assertions;
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
    void readWriteFIX() throws IOException {
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

        writer.write(HeaderType.create("AA", "BB"), HeartbeatType.create("req"));

        assertEquals(1, datas.size());

        final FixReadBuilder fixReadBuilder = FixReadBuilder.create(fixModel, globModel, HeaderType.TYPE);
        final FixReader reader = fixReadBuilder.createReader(new ByteArrayInputStream(datas.get(0))::read, (byte) 0x1);
        final FixMessageValue read = reader.read();
        assertNotNull(read);
        assertEquals("AA", read.header().get(HeaderType.SenderCompID));
        assertEquals("BB", read.header().get(HeaderType.TargetCompID));
        assertEquals("req", read.message().get(HeartbeatType.TestReqID));
    }

    @Test
    void readWriteGroup() throws IOException {
        final FixModel fixModel = ReadFixDictionary.parse("fix44", () ->
                new InputStreamReader(getClass().getClassLoader().getResourceAsStream("FIX44.xml"),
                        StandardCharsets.UTF_8), new FieldFactoryImpl());

        final GlobModel globModel = new DefaultGlobModel(HeartbeatType.TYPE, LogonType.TYPE);
        final FixWriterBuilder fixWriterBuilder = FixWriterBuilder.create(fixModel, globModel, HeaderType.TYPE);

        List<byte[]> datas = new ArrayList<>();
        final FixWriterBuilder.FixWriter writer = fixWriterBuilder.createWriter(new FixWriterImpl.Publish() {
            @Override
            public void publish(byte[] data, int start, int end) {
                datas.add(Arrays.copyOfRange(data, start, end));
            }
        });

        MutableGlob login = LogonType.create("crypt", LogonType.GroupMsgTypes.create("1", "1"),
                LogonType.GroupMsgTypes.create("2", "1"));

        writer.write(HeaderType.create("AA", "BB"), login);

        assertEquals(1, datas.size());

        final FixReadBuilder fixReadBuilder = FixReadBuilder.create(fixModel, globModel, HeaderType.TYPE);
        final FixReader reader = fixReadBuilder.createReader(new ByteArrayInputStream(datas.get(0))::read, (byte) 0x1);
        final FixMessageValue read = reader.read();
        assertNotNull(read);
        final Glob message = read.message();
        assertEquals("crypt", message.get(LogonType.EncryptMethod));
        final Glob[] globs = message.get(LogonType.groupMsgTypes);
        assertNotNull(globs);
        assertEquals(2, globs.length);
        final Glob gr1 = globs[0];
        final Glob gr2 = globs[1];
        assertEquals("1", gr1.get(LogonType.GroupMsgTypes.refMsgType));
        assertEquals("1", gr1.get(LogonType.GroupMsgTypes.msgDirection));
        assertEquals("2", gr2.get(LogonType.GroupMsgTypes.refMsgType));
        assertEquals("1", gr2.get(LogonType.GroupMsgTypes.msgDirection));
    }

    @Test
    void readComponent() throws IOException {
        final FixModel fixModel = ReadFixDictionary.parse("fix44", () ->
                new InputStreamReader(getClass().getClassLoader().getResourceAsStream("FIX44.xml"),
                        StandardCharsets.UTF_8), new FieldFactoryImpl());

        final GlobModel globModel = new DefaultGlobModel(HeartbeatType.TYPE, LogonType.TYPE,
                IndicationOfInterestType.TYPE, IndicationOfInterestType.InstrumentType.TYPE, IndicationOfInterestType.SecurityAltType.TYPE);
        final FixWriterBuilder fixWriterBuilder = FixWriterBuilder.create(fixModel, globModel, HeaderType.TYPE);

        List<byte[]> datas = new ArrayList<>();
        final FixWriterBuilder.FixWriter writer = fixWriterBuilder.createWriter(new FixWriterImpl.Publish() {
            @Override
            public void publish(byte[] data, int start, int end) {
                datas.add(Arrays.copyOfRange(data, start, end));
            }
        });

        Glob msg = IndicationOfInterestType.create("id1", "type1",
                IndicationOfInterestType.InstrumentType.create("EUR/USD",
                        IndicationOfInterestType.SecurityAltType.create("s1"),
                        IndicationOfInterestType.SecurityAltType.create("s2")));

        writer.write(HeaderType.create("AA", "BB"), msg);

        assertEquals(1, datas.size());

        final FixReadBuilder fixReadBuilder = FixReadBuilder.create(fixModel, globModel, HeaderType.TYPE);
        final FixReader reader = fixReadBuilder.createReader(new ByteArrayInputStream(datas.get(0))::read, (byte) 0x1);
        final FixMessageValue read = reader.read();
        assertNotNull(read);
        final Glob message = read.message();
        assertNotNull(message);
        assertEquals("id1", message.get(IndicationOfInterestType.IOIID));
        assertEquals("type1", message.get(IndicationOfInterestType.IOITransType));
        final Glob instrument = message.get(IndicationOfInterestType.Instrument);
        assertNotNull(instrument);
        assertEquals("EUR/USD", instrument.get(IndicationOfInterestType.InstrumentType.Symbol));
        final Glob[] secs = instrument.get(IndicationOfInterestType.InstrumentType.securityAltID);
        assertNotNull(secs);
        assertEquals(2, secs.length);
        assertEquals("s1", secs[0].get(IndicationOfInterestType.SecurityAltType.SecurityAltID));
        assertEquals("s2", secs[1].get(IndicationOfInterestType.SecurityAltType.SecurityAltID));
    }


    public static class HeaderType {
        public static final GlobType TYPE;

        public static final StringField SenderCompID;

        public static final StringField TargetCompID;

        public static final StringField MsgType;

        public static Glob create(String aa, String bb) {
            return TYPE.instantiate()
                    .set(SenderCompID, aa)
                    .set(TargetCompID, bb)
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

    public static class LogonType {
        public static final GlobType TYPE;

        public static final StringField EncryptMethod;

        @Target(GroupMsgTypes.class)
        public static final GlobArrayField groupMsgTypes;


        static {
            final GlobTypeBuilder typeBuilder = GlobTypeBuilderFactory.create("LogonType");
            typeBuilder.addAnnotation(FixMessageType.create("Logon"));
            EncryptMethod = typeBuilder.declareStringField("EncryptMethod", FixFieldType.create("EncryptMethod"));
            groupMsgTypes = typeBuilder.declareGlobArrayField("GroupMsgTypes", () -> GroupMsgTypes.TYPE);
            TYPE = typeBuilder.build();
        }

        public static MutableGlob create(String crypt, Glob...gr) {
            return TYPE.instantiate()
                    .set(EncryptMethod, crypt)
                    .set(groupMsgTypes, gr);
        }

        public static class GroupMsgTypes {
            public static final GlobType TYPE;

            public static final StringField refMsgType;

            public static final StringField msgDirection;

            public static Glob create(String ref, String direction) {
                return TYPE.instantiate()
                        .set(refMsgType, ref)
                        .set(msgDirection, direction);
            }

            static {
                final GlobTypeBuilder typeBuilder = GlobTypeBuilderFactory.create("GroupMsgTypes");
                typeBuilder.addAnnotation(FixGroupType.create("NoMsgTypes"));
                refMsgType = typeBuilder.declareStringField("RefMsgType", FixFieldType.create("RefMsgType"));
                msgDirection = typeBuilder.declareStringField("MsgDirection", FixFieldType.create("MsgDirection"));
                TYPE = typeBuilder.build();
            }
        }
    }

    public static class IndicationOfInterestType {
        public static final GlobType TYPE;

        public static final StringField IOIID;

        public static final StringField IOITransType;


        @Target(InstrumentType.class)
        public static final GlobField Instrument;

        public static Glob create(String ioiid, String type, Glob instr) {
            return TYPE.instantiate()
                    .set(IOIID, ioiid)
                    .set(IOITransType, type)
                    .set(Instrument, instr);
        }


        static {
            final GlobTypeBuilder typeBuilder = GlobTypeBuilderFactory.create("IndicationOfInterest");
            typeBuilder.addAnnotation(FixMessageType.create("IndicationOfInterest"));
            IOIID = typeBuilder.declareStringField("IOIID", FixFieldType.create("IOIID"));
            IOITransType = typeBuilder.declareStringField("IOITransType", FixFieldType.create("IOITransType"));
            Instrument = typeBuilder.declareGlobField("Instrument", () -> InstrumentType.TYPE);
            TYPE = typeBuilder.build();
        }

        public static class InstrumentType {
            public static final GlobType TYPE;

            public static final StringField Symbol;

            @Target(SecurityAltType.class)
            public static final GlobArrayField securityAltID;

            public static Glob create(String symbol, Glob... sec) {
                return TYPE.instantiate()
                        .set(Symbol, symbol)
                        .set(securityAltID, sec)
                        ;
            }

            static {
                final GlobTypeBuilder typeBuilder = GlobTypeBuilderFactory.create("Instrument");
                typeBuilder.addAnnotation(FixComponentType.create("Instrument"));
                Symbol = typeBuilder.declareStringField("Symbol", FixFieldType.create("Symbol"));
                securityAltID = typeBuilder.declareGlobArrayField("securityAltID", () -> SecurityAltType.TYPE);
                TYPE = typeBuilder.build();
            }
        }

        public static class SecurityAltType {
            public static final GlobType TYPE;

            public static final StringField SecurityAltID;

            public static Glob create(String sec) {
                return TYPE.instantiate()
                        .set(SecurityAltID, sec);
            }

            static {
                final GlobTypeBuilder typeBuilder = GlobTypeBuilderFactory.create("SecurityAltType");
                typeBuilder.addAnnotation(FixGroupType.create("NoSecurityAltID"));
                SecurityAltID = typeBuilder.declareStringField("SecurityAltID",
                        FixFieldType.create("SecurityAltID"));
                TYPE = typeBuilder.build();
            }
        }
    }
}