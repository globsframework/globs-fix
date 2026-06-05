package org.globsframework.fix.deserializer;

import org.globsframework.core.metamodel.GlobModel;
import org.globsframework.core.metamodel.GlobType;
import org.globsframework.core.metamodel.GlobTypeBuilder;
import org.globsframework.core.metamodel.GlobTypeBuilderFactory;
import org.globsframework.core.metamodel.annotations.Target;
import org.globsframework.core.metamodel.fields.GlobArrayField;
import org.globsframework.core.metamodel.fields.StringField;
import org.globsframework.core.metamodel.impl.DefaultGlobModel;
import org.globsframework.core.model.Glob;
import org.globsframework.core.model.MutableGlob;
import org.globsframework.fix.HeaderType;
import org.globsframework.fix.TrailerType;
import org.globsframework.fix.FormatDateTime;
import org.globsframework.fix.dictionary.FixModel;
import org.globsframework.fix.dictionary.admin.HeartbeatType;
import org.globsframework.fix.dictionary.admin.LogonType;
import org.globsframework.fix.dictionary.model.FixComponentType;
import org.globsframework.fix.dictionary.model.FixFieldType;
import org.globsframework.fix.dictionary.model.FixGroupType;
import org.globsframework.fix.dictionary.model.FixMessageType;
import org.globsframework.fix.dictionary.xml.FieldFactoryImpl;
import org.globsframework.fix.dictionary.xml.ReadFixDictionary;
import org.globsframework.fix.serializer.FixWriter;
import org.globsframework.fix.serializer.Publish;
import org.globsframework.fix.serializer.SerializerFixWriterBuilder;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class FixReaderBuilderTest {

    @Test
    void readWriteFIX() throws IOException {
        final FixModel fixModel = ReadFixDictionary.parse("fix44", () ->
                new InputStreamReader(getClass().getClassLoader().getResourceAsStream("FIX44.xml"),
                        StandardCharsets.UTF_8), new FieldFactoryImpl());

        final GlobModel globModel = new DefaultGlobModel(HeartbeatType.TYPE);
        final SerializerFixWriterBuilder fixWriterBuilder = SerializerFixWriterBuilder.create(fixModel, globModel,
                HeaderType.TYPE, TrailerType.TYPE, FormatDateTime.shouldRefreshUTC());

        List<byte[]> datas = new ArrayList<>();
        final FixWriter writer = fixWriterBuilder.createWriter(new Publish() {
            @Override
            public void publish(byte[] data, int offset, int length) {
                datas.add(Arrays.copyOfRange(data, offset, offset + length));
            }
        }, new BasicMsgSeqProvider());

        FormatDateTime utcFormater = FormatDateTime.shouldRefreshUTC();
        final ZonedDateTime headerTime = ZonedDateTime.parse("2026-04-01T20:33:30.123+02:00[Europe/Paris]");
        final String utc = utcFormater.now(headerTime.toInstant().toEpochMilli());
        writer.write(HeaderType.create("AA", "BB")
                        .set(HeaderType.sendingTime, utc), HeartbeatType.create("req1"),
                TrailerType.create("sign"), false);
        writer.write(HeaderType.create("CC", "DD")
                        .set(HeaderType.sendingTime, utc), HeartbeatType.create("req2"),
                TrailerType.create("sign"), false);

        assertEquals(2, datas.size());

        final FixReaderBuilder fixReaderBuilder = DeserializerFixReaderBuilder.create(fixModel, globModel, HeaderType.TYPE, TrailerType.TYPE);
        byte[] merged = new byte[datas.get(0).length + datas.get(1).length];
        System.arraycopy(datas.get(0), 0, merged, 0, datas.get(0).length);
        System.arraycopy(datas.get(1), 0, merged, datas.get(0).length, datas.get(1).length);
        final ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(merged);
        final FixReader reader = fixReaderBuilder.createReader(byteArrayInputStream::read);
        {
            final FixMessageValue read = reader.read();
            assertNotNull(read);
            assertEquals("AA", read.header().get(HeaderType.senderCompID));
            assertEquals("BB", read.header().get(HeaderType.targetCompID));
            assertEquals("req1", read.message().get(HeartbeatType.testReqID));
            assertEquals("0", read.header().get(HeaderType.msgType));
            assertEquals(headerTime.withZoneSameInstant(ZoneId.of("UTC")),
                    utcFormater.toDate(read.header().get(HeaderType.sendingTime)));

            assertNotNull(read.trailer());
            assertEquals("sign", read.trailer().get(TrailerType.Signature));
            assertEquals(130, read.trailer().get(TrailerType.CheckSum));
        }
        {
            final FixMessageValue read = reader.read();
            assertNotNull(read);
            assertEquals("CC", read.header().get(HeaderType.senderCompID));
            assertEquals("DD", read.header().get(HeaderType.targetCompID));
            assertEquals("req2", read.message().get(HeartbeatType.testReqID));
            assertEquals("0", read.header().get(HeaderType.msgType));

            assertNotNull(read.trailer());
            assertEquals("sign", read.trailer().get(TrailerType.Signature));
            assertEquals(140, read.trailer().get(TrailerType.CheckSum));
        }
    }

    @Test
    void readWriteGroup() throws IOException {
        final FixModel fixModel = ReadFixDictionary.parse("fix44", () ->
                new InputStreamReader(getClass().getClassLoader().getResourceAsStream("FIX44.xml"),
                        StandardCharsets.UTF_8), new FieldFactoryImpl());

        final GlobModel globModel = new DefaultGlobModel(HeartbeatType.TYPE, LogonType.TYPE);
        final SerializerFixWriterBuilder fixWriterBuilder =
                SerializerFixWriterBuilder.create(fixModel, globModel, HeaderType.TYPE, TrailerType.TYPE,
                        FormatDateTime.shouldRefreshUTC());

        List<byte[]> datas = new ArrayList<>();
        final FixWriter writer = fixWriterBuilder.createWriter(new Publish() {
            @Override
            public void publish(byte[] data, int offset, int length) {
                datas.add(Arrays.copyOfRange(data, offset, offset + length));
            }
        }, new BasicMsgSeqProvider());

        MutableGlob login = LogonType.create(1, LogonType.NoMsgTypes.create("1", "1"),
                LogonType.NoMsgTypes.create("2", "1"));

        writer.write(HeaderType.create("AA", "BB"), login, null, false);

        assertEquals(1, datas.size());

        final FixReaderBuilder fixReaderBuilder = DeserializerFixReaderBuilder.create(fixModel, globModel, HeaderType.TYPE, TrailerType.TYPE);
        final FixReader reader = fixReaderBuilder.createReader(new ByteArrayInputStream(datas.get(0))::read);
        final FixMessageValue read = reader.read();
        assertNotNull(read);
        final Glob message = read.message();
        assertEquals(1, message.get(LogonType.encryptMethod));
        final Glob[] globs = message.get(LogonType.msgTypes);
        assertNotNull(globs);
        assertEquals(2, globs.length);
        final Glob gr1 = globs[0];
        final Glob gr2 = globs[1];
        assertEquals("1", gr1.get(LogonType.NoMsgTypes.RefMsgType));
        assertEquals("1", gr1.get(LogonType.NoMsgTypes.MsgDirection));
        assertEquals("2", gr2.get(LogonType.NoMsgTypes.RefMsgType));
        assertEquals("1", gr2.get(LogonType.NoMsgTypes.MsgDirection));
    }

    @Test
    void readComponent() throws IOException {
        final FixModel fixModel = ReadFixDictionary.parse("fix44", () ->
                new InputStreamReader(getClass().getClassLoader().getResourceAsStream("FIX44.xml"),
                        StandardCharsets.UTF_8), new FieldFactoryImpl());

        final GlobModel globModel = new DefaultGlobModel(HeartbeatType.TYPE, LogonType.TYPE,
                IndicationOfInterestType.TYPE, IndicationOfInterestType.InstrumentType.TYPE, IndicationOfInterestType.SecurityAltType.TYPE);
        final SerializerFixWriterBuilder fixWriterBuilder =
                SerializerFixWriterBuilder.create(fixModel, globModel, HeaderType.TYPE, TrailerType.TYPE,
                        FormatDateTime.shouldRefreshUTC());

        List<byte[]> datas = new ArrayList<>();
        final FixWriter writer = fixWriterBuilder.createWriter(new Publish() {
            @Override
            public void publish(byte[] data, int offset, int length) {
                datas.add(Arrays.copyOfRange(data, offset, offset + length));
            }
        }, new BasicMsgSeqProvider());

        MutableGlob msg = IndicationOfInterestType.create("id1", "type1",
                "EUR/USD",
                        IndicationOfInterestType.SecurityAltType.create("s1"),
                        IndicationOfInterestType.SecurityAltType.create("s2"));

        writer.write(HeaderType.create("AA", "BB"), msg, null, false);

        assertEquals(1, datas.size());

        final FixReaderBuilder fixReaderBuilder = DeserializerFixReaderBuilder.create(fixModel, globModel, HeaderType.TYPE, TrailerType.TYPE);
        final FixReader reader = fixReaderBuilder.createReader(new ByteArrayInputStream(datas.get(0))::read);
        final FixMessageValue read = reader.read();
        assertNotNull(read);
        final Glob message = read.message();
        assertNotNull(message);
        assertEquals("id1", message.get(IndicationOfInterestType.IOIID));
        assertEquals("type1", message.get(IndicationOfInterestType.IOITransType));
        assertEquals("EUR/USD", message.get(IndicationOfInterestType.Symbol));
        final Glob[] secs = message.get(IndicationOfInterestType.securityAltID);
        assertNotNull(secs);
        assertEquals(2, secs.length);
        assertEquals("s1", secs[0].get(IndicationOfInterestType.SecurityAltType.SecurityAltID));
        assertEquals("s2", secs[1].get(IndicationOfInterestType.SecurityAltType.SecurityAltID));
    }


    public static class IndicationOfInterestType {
        public static final GlobType TYPE;

        public static final StringField IOIID;

        public static final StringField IOITransType;


        public static final StringField Symbol;

        @Target(SecurityAltType.class)
        public static final GlobArrayField securityAltID;


        public static MutableGlob create(String ioiid, String type, String symbol, Glob...sec) {
            return TYPE.instantiate()
                    .set(IOIID, ioiid)
                    .set(IOITransType, type)
                    .set(Symbol, symbol)
                    .set(securityAltID, sec);
        }

        static {
            final GlobTypeBuilder typeBuilder = GlobTypeBuilderFactory.create("IndicationOfInterest");
            typeBuilder.addAnnotation(FixMessageType.create("IndicationOfInterest"));
            IOIID = typeBuilder.declareStringField("IOIID", FixFieldType.create("IOIID"));
            IOITransType = typeBuilder.declareStringField("IOITransType", FixFieldType.create("IOITransType"));
            Symbol = typeBuilder.declareStringField("Symbol", FixFieldType.create("Symbol"));
            securityAltID = typeBuilder.declareGlobArrayField("securityAltID", () -> SecurityAltType.TYPE);
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