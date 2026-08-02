package org.globsframework.fix.deserializer;

import org.globsframework.core.metamodel.GlobModel;
import org.globsframework.core.metamodel.GlobType;
import org.globsframework.core.metamodel.GlobTypeBuilder;
import org.globsframework.core.metamodel.GlobTypeBuilderFactory;
import org.globsframework.core.metamodel.fields.StringField;
import org.globsframework.core.metamodel.impl.DefaultGlobModel;
import org.globsframework.fix.FormatDateTime;
import org.globsframework.fix.HeaderType;
import org.globsframework.fix.TrailerType;
import org.globsframework.fix.dictionary.FixModel;
import org.globsframework.fix.dictionary.model.FixFieldType;
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
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Message framing : BodyLength (tag 9) drives when the reader stops reading a message.
 */
class FixReaderFramingTest {
    private static final String SOH = Character.toString(1);

    /**
     * readHeader uses messageLen = 1000 as a sentinel until the real BodyLength is known : msgReadLen
     * must be reset first, otherwise a message whose BodyLength is exactly 1000 leaves msgReadLen at
     * 1000 and readNext refuses to read the BeginString of the next message.
     */
    @Test
    void aMessageOf1000BytesDoesNotBreakTheNextMessage() throws IOException {
        final String message = messageOfBodyLength(1000);
        final String clOrdId = clOrdIdOf(message);

        final FixReader reader = readerOn(message + message);

        assertEquals(clOrdId, reader.read().message().get(OrderType.clOrdID));
        assertEquals(clOrdId, reader.read().message().get(OrderType.clOrdID));
    }

    /**
     * Same sentinel, but tripped while reading the BeginString : BodyLength + the length of
     * "8=fix44<SOH>" must not add up to 1000 either.
     */
    @Test
    void aMessageEndingExactlyOnTheSentinelDoesNotBreakTheNextMessage() throws IOException {
        final int beginStringLength = ("8=fix44" + SOH).length();
        final String message = messageOfBodyLength(1000 - beginStringLength);
        final String clOrdId = clOrdIdOf(message);

        final FixReader reader = readerOn(message + message);

        assertEquals(clOrdId, reader.read().message().get(OrderType.clOrdID));
        assertEquals(clOrdId, reader.read().message().get(OrderType.clOrdID));
    }

    /**
     * A BodyLength shorter than the actual body makes msgReadLen jump over messageLen, and skipRemaining
     * (here on an unknown MsgType) then has nothing left to stop it : it used to read every following
     * message as the body of that one, up to the end of the stream.
     */
    @Test
    void aTooSmallBodyLengthOnASkippedMessageDoesNotSwallowTheStream() throws IOException {
        // ZZ is in no dictionary : the whole message is skipped by skipRemaining
        final String unknownMsgType = message("order1").replace(SOH + "35=D" + SOH, SOH + "35=ZZ" + SOH);
        final String garbled = withBodyLength(unknownMsgType, bodyLength(unknownMsgType) - 1);
        final String stream = garbled + message("order2").repeat(1000);
        final CountingByteReader input = new CountingByteReader(stream);
        final FixReader reader = readerOn(input);

        // the message is ignored, and since the stream is left misaligned (there is no
        // resynchronisation on the next BeginString) the reader gives up on the spot
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> assertThrows(RuntimeException.class, reader::read));

        assertTrue(input.read < 20_000,
                "the following messages were read as the body of the garbled one : " + input.read
                + " bytes consumed out of " + stream.length());
    }

    private static class CountingByteReader implements ByteReader {
        private final byte[] data;
        int read;

        CountingByteReader(String data) {
            this.data = data.getBytes(StandardCharsets.ISO_8859_1);
        }

        public int read(byte[] buf, int offset, int len) {
            if (read == data.length) {
                return -1;
            }
            final int count = Math.min(len, data.length - read);
            System.arraycopy(data, read, buf, offset, count);
            read += count;
            return count;
        }
    }

    /**
     * A BodyLength longer than the actual body : the reader runs into the trailer of the message,
     * detects the overshoot and ignores the message instead of reading the next ones as its own body.
     */
    @Test
    void aTooBigBodyLengthDoesNotSwallowTheStream() throws IOException {
        final String garbled = withBodyLength(message("order1"), bodyLength(message("order1")) + 1);
        final String next = message("order2");

        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            final FixReader reader = readerOn(garbled + next);
            assertEquals("order2", reader.read().message().get(OrderType.clOrdID));
        });
    }

    /**
     * BodyLength = 0 : the header cannot be read at all. The message must be ignored, and must never
     * come out as a FixMessageValue with a null header decoded with the previous message structure.
     */
    @Test
    void anEmptyBodyIsIgnoredAndDoesNotLeakANullHeader() throws IOException {
        // 204 is the real checksum of that truncated message, so only the header check can reject it
        final String garbled = "8=fix44" + SOH + "9=0" + SOH + "10=204" + SOH;

        final FixReader reader = readerOn(message("order1") + garbled + message("order2"));

        final FixMessageValue first = reader.read();
        assertEquals("order1", first.message().get(OrderType.clOrdID));
        assertNotNull(first.header());

        final FixMessageValue second = reader.read();
        assertNotNull(second.header());
        assertEquals("order2", second.message().get(OrderType.clOrdID));
    }

    /**
     * A body holding only MsgType, again too short for a complete header.
     */
    @Test
    void aBodyWithOnlyAMsgTypeIsIgnored() throws IOException {
        final String body = "35=D" + SOH;
        final String truncated = "8=fix44" + SOH + "9=" + body.length() + SOH + body;
        final String garbled = truncated + "10=" + String.format("%03d", checkSum(truncated)) + SOH;

        final FixReader reader = readerOn(garbled + message("order2"));

        final FixMessageValue value = reader.read();
        assertNotNull(value.header());
        assertEquals("order2", value.message().get(OrderType.clOrdID));
    }

    private static int checkSum(String message) {
        int sum = 0;
        for (byte b : message.getBytes(StandardCharsets.ISO_8859_1)) {
            sum += b & 0xFF;
        }
        return sum % 256;
    }

    /**
     * ClOrdID is padded until the message has exactly the wanted BodyLength.
     */
    private String messageOfBodyLength(int wantedBodyLength) throws IOException {
        final String clOrdId = "order" + wantedBodyLength;
        final int delta = wantedBodyLength - bodyLength(message(clOrdId));
        assertTrue(delta >= 0, "message already longer than " + wantedBodyLength);
        final String message = message(clOrdId + "X".repeat(delta));
        assertEquals(wantedBodyLength, bodyLength(message));
        return message;
    }

    private static String clOrdIdOf(String message) {
        final int at = message.indexOf(SOH + "11=");
        return message.substring(at + 4, message.indexOf(SOH, at + 4));
    }

    private static int bodyLength(String message) {
        final int at = message.indexOf(SOH + "9=");
        return Integer.parseInt(message.substring(at + 3, message.indexOf(SOH, at + 3)));
    }

    private static String withBodyLength(String message, int bodyLength) {
        final int at = message.indexOf(SOH + "9=");
        return message.substring(0, at + 3) + bodyLength + message.substring(message.indexOf(SOH, at + 3));
    }

    private FixModel readDictionary() throws IOException {
        return ReadFixDictionary.parse("fix44", () ->
                new InputStreamReader(getClass().getClassLoader().getResourceAsStream("FIX44.xml"),
                        StandardCharsets.UTF_8), new FieldFactoryImpl());
    }

    private GlobModel globModel() {
        return new DefaultGlobModel(OrderType.TYPE);
    }

    private FixReader readerOn(String stream) throws IOException {
        final ByteArrayInputStream input = new ByteArrayInputStream(stream.getBytes(StandardCharsets.ISO_8859_1));
        return readerOn(input::read);
    }

    private FixReader readerOn(ByteReader input) throws IOException {
        return DeserializerFixReaderBuilder.create(readDictionary(), globModel(), HeaderType.TYPE, TrailerType.TYPE)
                .createReader(input);
    }

    private String message(String clOrdId) throws IOException {
        final SerializerFixWriterBuilder builder = SerializerFixWriterBuilder.create(readDictionary(), globModel(),
                HeaderType.TYPE, TrailerType.TYPE, FormatDateTime.shouldRefreshUTC());
        final List<byte[]> datas = new ArrayList<>();
        final FixWriter writer = builder.createWriter(
                (data, offset, length) -> datas.add(Arrays.copyOfRange(data, offset, offset + length)),
                new BasicMsgSeqProvider());

        writer.write(HeaderType.create("AA", "BB"), OrderType.TYPE.instantiate().set(OrderType.clOrdID, clOrdId),
                null, false);

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
}
