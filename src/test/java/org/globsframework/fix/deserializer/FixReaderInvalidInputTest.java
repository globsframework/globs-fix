package org.globsframework.fix.deserializer;

import org.globsframework.core.metamodel.GlobModel;
import org.globsframework.core.metamodel.GlobType;
import org.globsframework.core.metamodel.GlobTypeBuilder;
import org.globsframework.core.metamodel.GlobTypeBuilderFactory;
import org.globsframework.core.metamodel.fields.GlobArrayField;
import org.globsframework.core.metamodel.fields.StringField;
import org.globsframework.core.metamodel.impl.DefaultGlobModel;
import org.globsframework.core.model.Glob;
import org.globsframework.core.model.MutableGlob;
import org.globsframework.fix.FormatDateTime;
import org.globsframework.fix.HeaderType;
import org.globsframework.fix.TrailerType;
import org.globsframework.fix.dictionary.FixModel;
import org.globsframework.fix.dictionary.admin.RejectType;
import org.globsframework.fix.dictionary.model.FixFieldType;
import org.globsframework.fix.dictionary.model.FixGroupType;
import org.globsframework.fix.dictionary.model.FixMessageType;
import org.globsframework.fix.dictionary.xml.FieldFactoryImpl;
import org.globsframework.fix.dictionary.xml.ReadFixDictionary;
import org.globsframework.fix.serializer.FixWriter;
import org.globsframework.fix.serializer.SerializerFixWriterBuilder;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What the reader does with values it did not write itself : unknown version, oversized counts,
 * tags belonging to no known structure.
 */
class FixReaderInvalidInputTest {
    private static final String SOH = Character.toString(1);

    @Test
    void theVersionMismatchReportsOnlyTheVersionItGot() throws IOException {
        final String message = message("order1").replace("8=fix44" + SOH, "8=FIX.4.2" + SOH);

        final RuntimeException exception = assertThrows(RuntimeException.class, readerOn(message)::read);

        assertEquals(RuntimeException.class, exception.getClass());
        assertEquals("invalid version. fix44 was expected but got FIX.4.2", exception.getMessage());
    }

    /**
     * The version was reported with new String(buffer, from, endAt), endAt being an index and not a
     * length : past the middle of the 10k buffer that overflows the array.
     */
    @Test
    void theVersionMismatchDoesNotOverflowTheBufferWhenDetectedLate() throws IOException {
        final String valid = message("order1");
        final String message = valid.replace("8=fix44" + SOH, "8=FIX.4.2" + SOH);
        // enough valid messages for the bad one to sit past the middle of the reader buffer
        final String stream = valid.repeat(6000 / valid.length() + 1) + message;
        final FixReader reader = readerOn(stream);

        RuntimeException exception = null;
        for (int i = 0; exception == null; i++) {
            assertTrue(i < 1000, "the invalid version was never detected");
            try {
                reader.read();
            } catch (RuntimeException e) {
                exception = e;
            }
        }

        assertEquals(RuntimeException.class, exception.getClass());
        assertEquals("invalid version. fix44 was expected but got FIX.4.2", exception.getMessage());
    }

    /**
     * The group count sizes an array before any element is read : a message cannot hold more elements
     * than it has bytes left. It used to be allocated as announced, ie an OutOfMemoryError that the
     * decode error handling does not catch.
     */
    @Test
    void anOversizedGroupCountIsRejectedInsteadOfAllocated() throws IOException {
        final String message = assemble(bodyOf(newsWithOneGroup())
                .replace(SOH + "146=1" + SOH, SOH + "146=999999999" + SOH));

        final FixMessageValue value = readerOn(message).read();

        assertNotNull(value.decodeError());
        assertEquals(RejectType.SESSION_REJECT_INCORRECT_DATA_FORMAT, value.decodeError().sessionRejectReason());
        assertTrue(value.decodeError().text().contains("Invalid count 999999999 for group 146"),
                value.decodeError().text());
    }

    @Test
    void anOversizedBodyLengthIsRefused() throws IOException {
        final String message = message("order1").replaceFirst(SOH + "9=[0-9]+" + SOH, SOH + "9=1048577" + SOH);

        final RuntimeException exception = assertThrows(RuntimeException.class, readerOn(message)::read);

        assertEquals("Invalid BodyLength 1048577, 1048576 max.", exception.getMessage());
    }

    /**
     * A tag defined in no dictionary : the body cannot be fully decoded, but the message frames and
     * checksums correctly so it must come back as a decode error and leave the stream usable.
     */
    @Test
    void aTagBelongingToNoStructureIsRejectedAndTheStreamGoesOn() throws IOException {
        final String garbled = assemble(bodyOf(message("order1"))
                .replace(SOH + "11=order1" + SOH, SOH + "11=order1" + SOH + "9999=1" + SOH));

        final FixReader reader = readerOn(garbled + message("order2"));

        final FixMessageValue rejected = reader.read();
        assertNotNull(rejected.header());
        assertNotNull(rejected.decodeError());
        assertEquals(RejectType.SESSION_REJECT_INVALID_TAG_NUMBER, rejected.decodeError().sessionRejectReason());
        assertEquals("Tag '9999' not expected in message 'D'", rejected.decodeError().text());

        assertEquals("order2", reader.read().message().get(OrderType.clOrdID));
    }

    /**
     * Same, but the unknown tag is the last one of the body : nothing is left to skip.
     */
    @Test
    void aTrailingTagBelongingToNoStructureIsRejectedAndTheStreamGoesOn() throws IOException {
        final String garbled = assemble(bodyOf(message("order1")) + "9999=1" + SOH);

        final FixReader reader = readerOn(garbled + message("order2"));

        assertEquals(RejectType.SESSION_REJECT_INVALID_TAG_NUMBER, reader.read().decodeError().sessionRejectReason());
        assertEquals("order2", reader.read().message().get(OrderType.clOrdID));
    }

    /**
     * The body between the BodyLength field and the CheckSum field, ie exactly what BodyLength counts.
     */
    private static String bodyOf(String message) {
        final int at = message.indexOf(SOH, message.indexOf(SOH + "9=") + 1) + 1;
        return message.substring(at, message.lastIndexOf(SOH + "10=") + 1);
    }

    /**
     * Rebuilds BeginString / BodyLength / CheckSum around a modified body.
     */
    private static String assemble(String body) {
        final String message = "8=fix44" + SOH + "9=" + body.length() + SOH + body;
        int sum = 0;
        for (byte b : message.getBytes(StandardCharsets.ISO_8859_1)) {
            sum += b & 0xFF;
        }
        return message + "10=" + String.format("%03d", sum % 256) + SOH;
    }

    private FixModel readDictionary() throws IOException {
        return ReadFixDictionary.parse("fix44", () ->
                new InputStreamReader(getClass().getClassLoader().getResourceAsStream("FIX44.xml"),
                        StandardCharsets.UTF_8), new FieldFactoryImpl());
    }

    private GlobModel globModel() {
        return new DefaultGlobModel(OrderType.TYPE, NewsType.TYPE);
    }

    private FixReader readerOn(String stream) throws IOException {
        final ByteArrayInputStream input = new ByteArrayInputStream(stream.getBytes(StandardCharsets.ISO_8859_1));
        return DeserializerFixReaderBuilder.create(readDictionary(), globModel(), HeaderType.TYPE, TrailerType.TYPE)
                .createReader(input::read);
    }

    private String message(String clOrdId) throws IOException {
        return write(OrderType.TYPE.instantiate().set(OrderType.clOrdID, clOrdId));
    }

    private String newsWithOneGroup() throws IOException {
        return write(NewsType.TYPE.instantiate()
                .set(NewsType.relatedSym, new Glob[]{NewsType.RelatedSymType.TYPE.instantiate()
                        .set(NewsType.RelatedSymType.symbol, "ACME")}));
    }

    private String write(MutableGlob message) throws IOException {
        final SerializerFixWriterBuilder builder = SerializerFixWriterBuilder.create(readDictionary(), globModel(),
                HeaderType.TYPE, TrailerType.TYPE, FormatDateTime.shouldRefreshUTC());
        final List<byte[]> datas = new ArrayList<>();
        final FixWriter writer = builder.createWriter(
                (data, offset, length) -> datas.add(Arrays.copyOfRange(data, offset, offset + length)),
                new BasicMsgSeqProvider());

        writer.write(HeaderType.create("AA", "BB"), message, null, false);

        assertEquals(1, datas.size());
        return new String(datas.getFirst(), StandardCharsets.ISO_8859_1);
    }

    public static class OrderType {
        public static final GlobType TYPE;
        public static final StringField clOrdID;

        static {
            final GlobTypeBuilder builder = GlobTypeBuilderFactory.create("NewOrderSingle");
            builder.addAnnotation(FixMessageType.create("NewOrderSingle"));
            clOrdID = builder.declareStringField("clOrdID", FixFieldType.create("ClOrdID"));
            TYPE = builder.build();
        }
    }

    public static class NewsType {
        public static final GlobType TYPE;

        public static final GlobArrayField<RelatedSymType> relatedSym;

        static {
            final GlobTypeBuilder builder = GlobTypeBuilderFactory.create("News");
            builder.addAnnotation(FixMessageType.create("News"));
            relatedSym = builder.declareGlobArrayField("relatedSym", () -> RelatedSymType.TYPE);
            TYPE = builder.build();
        }

        public static class RelatedSymType {
            public static final GlobType TYPE;
            public static final StringField symbol;

            static {
                final GlobTypeBuilder builder = GlobTypeBuilderFactory.create("RelatedSym");
                builder.addAnnotation(FixGroupType.create("NoRelatedSym"));
                symbol = builder.declareStringField("symbol", FixFieldType.create("Symbol"));
                TYPE = builder.build();
            }
        }
    }
}
