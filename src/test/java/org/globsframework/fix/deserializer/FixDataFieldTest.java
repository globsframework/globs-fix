package org.globsframework.fix.deserializer;

import org.globsframework.core.metamodel.GlobModel;
import org.globsframework.core.metamodel.GlobType;
import org.globsframework.core.metamodel.GlobTypeBuilder;
import org.globsframework.core.metamodel.GlobTypeBuilderFactory;
import org.globsframework.core.metamodel.annotations.Target;
import org.globsframework.core.metamodel.fields.BytesField;
import org.globsframework.core.metamodel.fields.GlobArrayField;
import org.globsframework.core.metamodel.fields.StringField;
import org.globsframework.core.metamodel.impl.DefaultGlobModel;
import org.globsframework.core.model.Glob;
import org.globsframework.core.model.MutableGlob;
import org.globsframework.fix.FormatDateTime;
import org.globsframework.fix.HeaderType;
import org.globsframework.fix.TrailerType;
import org.globsframework.fix.dictionary.FixModel;
import org.globsframework.fix.dictionary.model.FixFieldType;
import org.globsframework.fix.dictionary.model.FixGroupType;
import org.globsframework.fix.dictionary.model.FixMessageType;
import org.globsframework.fix.dictionary.reverter.FixModelToGlobType;
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
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FIX DATA fields : their content is any sequence of bytes, SOH and '=' included, and their size is
 * given by the LENGTH field that immediately precedes them.
 */
class FixDataFieldTest {
    private static final String SOH = Character.toString(1);

    @Test
    void aPayloadHoldingSeparatorsIsReadBack() throws IOException {
        final byte[] payload = {'a', 0x1, 'b', '=', 'c', 0x0, (byte) 0xFF, 0x1, '1', '0', '=', '0', '0', '0', 0x1};
        final MutableGlob news = NewsType.TYPE.instantiate()
                .set(NewsType.headline, "flash")
                .set(NewsType.rawData, payload);

        final String written = new String(write(news), StandardCharsets.ISO_8859_1);
        assertTrue(written.contains("95=" + payload.length + SOH + "96="), written);

        assertArrayEquals(payload, readBack(written).get(NewsType.rawData));
    }

    /**
     * The payload is read straight into its own array : it is not limited by the size of the reader
     * buffer, which is 10 KB.
     */
    @Test
    void aPayloadLargerThanTheReadBufferIsReadBack() throws IOException {
        final byte[] payload = new byte[50_000];
        new Random(0).nextBytes(payload);
        final MutableGlob news = NewsType.TYPE.instantiate()
                .set(NewsType.headline, "flash")
                .set(NewsType.rawData, payload);

        assertArrayEquals(payload, readBack(new String(write(news), StandardCharsets.ISO_8859_1))
                .get(NewsType.rawData));
    }

    @Test
    void aDataFieldInsideARepeatingGroupIsReadBack() throws IOException {
        final byte[] first = {0x1, 'o', 'n', 'e'};
        final byte[] second = {'t', 'w', 'o', '='};
        final MutableGlob news = NewsType.TYPE.instantiate()
                .set(NewsType.headline, "flash")
                .set(NewsType.lines, new Glob[]{
                        NewsType.LineType.TYPE.instantiate().set(NewsType.LineType.text, "l1")
                                .set(NewsType.LineType.encodedText, first),
                        NewsType.LineType.TYPE.instantiate().set(NewsType.LineType.text, "l2")
                                .set(NewsType.LineType.encodedText, second)});

        final Glob[] lines = readBack(new String(write(news), StandardCharsets.ISO_8859_1)).get(NewsType.lines);

        assertEquals(2, lines.length);
        assertArrayEquals(first, lines[0].get(NewsType.LineType.encodedText));
        assertArrayEquals(second, lines[1].get(NewsType.LineType.encodedText));
    }

    /**
     * The length drives the parsing whether or not the GlobType binds the pair : a reader that knows
     * nothing of RawData must still step over its payload rather than scan it.
     */
    @Test
    void anUnboundDataFieldIsSteppedOver() throws IOException {
        final byte[] payload = {'a', 0x1, 'b', '=', 'c'};
        final String message = new String(write(NewsType.TYPE.instantiate()
                .set(NewsType.headline, "flash")
                .set(NewsType.rawData, payload)), StandardCharsets.ISO_8859_1);

        final FixReader reader = readerOn(message + message, new DefaultGlobModel(NewsWithoutDataType.TYPE));

        assertEquals("flash", reader.read().message().get(NewsWithoutDataType.headline));
        assertEquals("flash", reader.read().message().get(NewsWithoutDataType.headline));
    }

    /**
     * A DATA field bound to a StringField, as the test TrailerType binds Signature : the length is
     * written from the payload even when the GlobType does not declare SignatureLength.
     */
    @Test
    void aDataFieldBoundToAStringFieldIsWrittenWithItsLength() throws IOException {
        final String written = new String(write(NewsType.TYPE.instantiate().set(NewsType.headline, "flash"),
                TrailerType.create("sig")), StandardCharsets.ISO_8859_1);

        assertTrue(written.contains(SOH + "93=3" + SOH + "89=sig" + SOH), written);
        assertEquals("sig", readBack(written, TrailerType.TYPE).trailer().get(TrailerType.Signature));
    }

    @Test
    void aLengthOfZeroIsNotFollowedByADataField() throws IOException {
        final String message = assemble(bodyOf(new String(write(NewsType.TYPE.instantiate()
                .set(NewsType.headline, "flash")), StandardCharsets.ISO_8859_1)) + "95=0" + SOH);

        final FixMessageValue value = readerOn(message, globModel()).read();

        assertNull(value.decodeError());
        assertEquals("flash", value.message().get(NewsType.headline));
        assertNull(value.message().get(NewsType.rawData));
    }

    @Test
    void aLengthLargerThanTheRestOfTheMessageIsRejected() throws IOException {
        final FixMessageValue value = readerOn(assemble(newsBody("999999", "abcd")), globModel()).read();

        assertNotNull(value.decodeError());
        assertTrue(value.decodeError().text().contains("Invalid length 999999 for data field 96"),
                value.decodeError().text());
    }

    /**
     * A length that runs past the end of the field leaves the stream out of step with the message :
     * nothing can be trusted any more, so the message is ignored and the reader gives up rather than
     * hand out a payload made of whatever followed.
     */
    @Test
    void aLengthThatDoesNotEndOnASeparatorIsNotAccepted() throws IOException {
        // 6 instead of 4 : eats the separator and the first byte of the checksum
        final String message = assemble(newsBody("6", "abcd"));

        assertThrows(RuntimeException.class, readerOn(message, globModel())::read);
    }

    /**
     * The announced length is honoured even when the tag that follows is not the expected one : the
     * message is rejected but the stream stays in step.
     */
    @Test
    void aTagOtherThanTheDataFieldAfterTheLengthIsRejected() throws IOException {
        final String garbled = assemble(newsBody("4", "abcd").replace(SOH + "96=", SOH + "97="));
        final String good = new String(write(NewsType.TYPE.instantiate().set(NewsType.headline, "next")),
                StandardCharsets.ISO_8859_1);

        final FixReader reader = readerOn(garbled + good, globModel());

        final FixMessageValue value = reader.read();
        assertNotNull(value.decodeError());
        assertTrue(value.decodeError().text().contains("Data field 96 expected after its length but got 97"),
                value.decodeError().text());
        assertEquals("next", reader.read().message().get(NewsType.headline));
    }

    @Test
    void aRejectedDataFieldDoesNotBreakTheFollowingMessage() throws IOException {
        final String good = new String(write(NewsType.TYPE.instantiate().set(NewsType.headline, "next")),
                StandardCharsets.ISO_8859_1);

        final FixReader reader = readerOn(assemble(newsBody("999999", "abcd")) + good, globModel());

        assertNotNull(reader.read().decodeError());
        assertEquals("next", reader.read().message().get(NewsType.headline));
    }

    @Test
    void dataFieldsAreGeneratedAsBytesFields() throws IOException {
        final GlobType news = Arrays.stream(FixModelToGlobType.toType(readDictionary()).messages())
                .filter(message -> message.getAnnotation(FixMessageType.UNIQUE_KEY).get(FixMessageType.name).equals("B"))
                .findFirst().orElseThrow();

        assertInstanceOf(BytesField.class, news.getField("RawData"));
        assertInstanceOf(BytesField.class, news.getField("EncodedHeadline"));
    }

    /**
     * A News message built by hand : the writer holds its field writers in a map, so the order it
     * puts them on the wire is not fixed, and these tests need the data field to be the last one.
     */
    private static String newsBody(String rawDataLength, String rawData) {
        return "35=B" + SOH + "49=AA" + SOH + "56=BB" + SOH + "34=1" + SOH
               + "52=20260101-00:00:00.000" + SOH + "148=flash" + SOH
               + "95=" + rawDataLength + SOH + "96=" + rawData + SOH;
    }

    private static String bodyOf(String message) {
        final int at = message.indexOf(SOH, message.indexOf(SOH + "9=") + 1) + 1;
        return message.substring(at, message.lastIndexOf(SOH + "10=") + 1);
    }

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
        return new DefaultGlobModel(NewsType.TYPE);
    }

    private FixReader readerOn(String stream, GlobModel globModel) throws IOException {
        return readerOn(stream, globModel, TrailerType.TYPE);
    }

    private FixReader readerOn(String stream, GlobModel globModel, GlobType trailerType) throws IOException {
        final ByteArrayInputStream input = new ByteArrayInputStream(stream.getBytes(StandardCharsets.ISO_8859_1));
        return DeserializerFixReaderBuilder.create(readDictionary(), globModel, HeaderType.TYPE, trailerType)
                .createReader(input::read);
    }

    private Glob readBack(String message) throws IOException {
        return readBack(message, TrailerType.TYPE).message();
    }

    private FixMessageValue readBack(String message, GlobType trailerType) throws IOException {
        final FixMessageValue read = readerOn(message, globModel(), trailerType).read();
        assertNull(read.decodeError());
        return read;
    }

    private byte[] write(MutableGlob message) throws IOException {
        return write(message, null);
    }

    private byte[] write(MutableGlob message, MutableGlob trailer) throws IOException {
        final SerializerFixWriterBuilder builder = SerializerFixWriterBuilder.create(readDictionary(), globModel(),
                HeaderType.TYPE, TrailerType.TYPE, FormatDateTime.shouldRefreshUTC());
        final List<byte[]> datas = new ArrayList<>();
        final FixWriter writer = builder.createWriter(
                (data, offset, length) -> datas.add(Arrays.copyOfRange(data, offset, offset + length)),
                new BasicMsgSeqProvider());

        writer.write(HeaderType.create("AA", "BB"), message, trailer, false);

        assertEquals(1, datas.size());
        return datas.getFirst();
    }

    public static class NewsType {
        public static final GlobType TYPE;
        public static final StringField headline;
        public static final BytesField rawData;

        @Target(LineType.class)
        public static final GlobArrayField lines;

        static {
            final GlobTypeBuilder builder = GlobTypeBuilderFactory.create("News");
            builder.addAnnotation(FixMessageType.create("News"));
            headline = builder.declareStringField("headline", FixFieldType.create("Headline"));
            lines = builder.declareGlobArrayField("lines", () -> LineType.TYPE);
            rawData = builder.declareBytesField("rawData", FixFieldType.create("RawData"));
            TYPE = builder.build();
        }

        public static class LineType {
            public static final GlobType TYPE;
            public static final StringField text;
            public static final BytesField encodedText;

            static {
                final GlobTypeBuilder builder = GlobTypeBuilderFactory.create("LinesOfText");
                builder.addAnnotation(FixGroupType.create("LinesOfText"));
                text = builder.declareStringField("text", FixFieldType.create("Text"));
                encodedText = builder.declareBytesField("encodedText", FixFieldType.create("EncodedText"));
                TYPE = builder.build();
            }
        }
    }

    /**
     * The same message without the RawData pair : the reader must step over it all the same.
     */
    public static class NewsWithoutDataType {
        public static final GlobType TYPE;
        public static final StringField headline;

        static {
            final GlobTypeBuilder builder = GlobTypeBuilderFactory.create("News");
            builder.addAnnotation(FixMessageType.create("News"));
            headline = builder.declareStringField("headline", FixFieldType.create("Headline"));
            TYPE = builder.build();
        }
    }
}
