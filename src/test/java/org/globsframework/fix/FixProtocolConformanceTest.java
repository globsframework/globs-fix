package org.globsframework.fix;

import org.globsframework.core.metamodel.GlobModel;
import org.globsframework.core.metamodel.impl.DefaultGlobModel;
import org.globsframework.fix.deserializer.BasicMsgSeqProvider;
import org.globsframework.fix.deserializer.DeserializerFixReaderBuilder;
import org.globsframework.fix.deserializer.FixMessageValue;
import org.globsframework.fix.deserializer.FixReader;
import org.globsframework.fix.deserializer.FixReaderBuilder;
import org.globsframework.fix.dictionary.FixModel;
import org.globsframework.fix.dictionary.admin.HeartbeatType;
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

/*
Check the wire format against the FIX 4.4 specification :
 - BeginString(8), BodyLength(9) and MsgType(35) are the first three fields, in that order.
 - BodyLength counts the bytes starting after the SOH of BodyLength up to and including the SOH
   that precedes the CheckSum field.
 - CheckSum is the sum modulo 256 of every byte of the message up to and including the SOH that
   precedes the CheckSum field, always transmitted on exactly 3 digits.
 - The message ends with SOH.
 - MsgSeqNum(34) and SendingTime(52 as YYYYMMDD-HH:MM:SS.sss) are present in the header.
Also check that the reader enforces BeginString/CheckSum and supports a fragmented TCP stream.
 */
public class FixProtocolConformanceTest {
    private static final byte SOH = 0x1;

    private FixModel loadModel() throws IOException {
        return ReadFixDictionary.parse("FIX.4.4", () ->
                new InputStreamReader(getClass().getClassLoader().getResourceAsStream("FIX44.xml"),
                        StandardCharsets.UTF_8), new FieldFactoryImpl());
    }

    private FixWriter createWriter(FixModel fixModel, GlobModel globModel, List<byte[]> out) {
        final SerializerFixWriterBuilder builder = SerializerFixWriterBuilder.create(fixModel, globModel,
                HeaderType.TYPE, TrailerType.TYPE, FormatDateTime.shouldRefreshUTC());
        return builder.createWriter((data, offset, length) ->
                out.add(Arrays.copyOfRange(data, offset, offset + length)), new BasicMsgSeqProvider());
    }

    @Test
    void writerProducesSpecCompliantMessages() throws IOException {
        final FixModel fixModel = loadModel();
        final GlobModel globModel = new DefaultGlobModel(HeartbeatType.TYPE);
        List<byte[]> datas = new ArrayList<>();
        final FixWriter writer = createWriter(fixModel, globModel, datas);

        writer.write(HeaderType.create("SENDER", "TARGET"), HeartbeatType.create("req1"), null, false);

        assertEquals(1, datas.size());
        final byte[] msg = datas.get(0);
        final List<String> fields = splitFields(msg);

        assertEquals("8=FIX.4.4", fields.get(0), "BeginString must be the first field");
        assertTrue(fields.get(1).startsWith("9="), "BodyLength must be the second field");
        assertEquals("35=0", fields.get(2), "MsgType must be the third field");

        assertConformant(msg);

        final String seqNum = findField(fields, 34);
        assertEquals("1", seqNum, "first message must have MsgSeqNum 1");
        final String sendingTime = findField(fields, 52);
        assertNotNull(sendingTime, "SendingTime(52) is required in the header");
        assertTrue(sendingTime.matches("\\d{8}-\\d{2}:\\d{2}:\\d{2}\\.\\d{3}"),
                "SendingTime must be formatted YYYYMMDD-HH:MM:SS.sss but was " + sendingTime);
    }

    @Test
    void checkSumIsAlwaysOnThreeDigitsAndBodyLengthExact() throws IOException {
        final FixModel fixModel = loadModel();
        final GlobModel globModel = new DefaultGlobModel(HeartbeatType.TYPE);
        List<byte[]> datas = new ArrayList<>();
        final FixWriter writer = createWriter(fixModel, globModel, datas);

        // enough messages to cover checksums below 10, below 100 and above.
        for (int i = 0; i < 300; i++) {
            writer.write(HeaderType.create("SENDER", "TARGET"), HeartbeatType.create("req-" + i), null, false);
        }
        assertEquals(300, datas.size());
        for (byte[] data : datas) {
            assertConformant(data);
        }
    }

    @Test
    void readerAcceptsOneByteAtATimeStream() throws IOException {
        final FixModel fixModel = loadModel();
        final GlobModel globModel = new DefaultGlobModel(HeartbeatType.TYPE);
        List<byte[]> datas = new ArrayList<>();
        final FixWriter writer = createWriter(fixModel, globModel, datas);

        writer.write(HeaderType.create("SENDER", "TARGET"), HeartbeatType.create("req1"), null, false);
        writer.write(HeaderType.create("SENDER", "TARGET"), HeartbeatType.create("req2"), null, false);
        writer.write(HeaderType.create("SENDER", "TARGET"), HeartbeatType.create("req3"), null, false);

        final byte[] merged = merge(datas);
        final ByteArrayInputStream stream = new ByteArrayInputStream(merged);
        final FixReaderBuilder readerBuilder = DeserializerFixReaderBuilder.create(fixModel, globModel,
                HeaderType.TYPE, TrailerType.TYPE);
        // a TCP read can return any number of bytes : the reader must resynchronize between reads.
        final FixReader reader = readerBuilder.createReader((buf, offset, len) ->
                stream.read(buf, offset, len <= 0 ? 0 : 1));

        for (int i = 1; i <= 3; i++) {
            final FixMessageValue read = reader.read();
            assertNotNull(read);
            assertEquals("req" + i, read.message().get(HeartbeatType.testReqID));
            assertEquals(i, read.header().get(HeaderType.msgSeqNum));
        }
    }

    @Test
    void readerRejectsCorruptedCheckSum() throws IOException {
        final FixModel fixModel = loadModel();
        final GlobModel globModel = new DefaultGlobModel(HeartbeatType.TYPE);
        List<byte[]> datas = new ArrayList<>();
        final FixWriter writer = createWriter(fixModel, globModel, datas);
        writer.write(HeaderType.create("SENDER", "TARGET"), HeartbeatType.create("req1"), null, false);

        final byte[] msg = datas.get(0);
        // corrupt one byte of the body without touching the transmitted checksum
        final int at = indexOf(msg, "112=req1".getBytes(StandardCharsets.ISO_8859_1));
        assertTrue(at > 0);
        msg[at + 4] = 'X';

        final FixReaderBuilder readerBuilder = DeserializerFixReaderBuilder.create(fixModel, globModel,
                HeaderType.TYPE, TrailerType.TYPE);
        final FixReader reader = readerBuilder.createReader(new ByteArrayInputStream(msg)::read);
        final RuntimeException exception = assertThrows(RuntimeException.class, reader::read);
        assertTrue(exception.getMessage().contains("checksum"),
                "expected a checksum error but got : " + exception.getMessage());
    }

    @Test
    void readerRejectsUnexpectedBeginString() throws IOException {
        final FixModel fixModel = loadModel();
        final GlobModel globModel = new DefaultGlobModel(HeartbeatType.TYPE);
        List<byte[]> datas = new ArrayList<>();
        final FixWriter writer = createWriter(fixModel, globModel, datas);
        writer.write(HeaderType.create("SENDER", "TARGET"), HeartbeatType.create("req1"), null, false);

        final byte[] msg = datas.get(0);
        // FIX.4.4 -> FIX.4.2 (same length, BodyLength still valid)
        msg[8] = '2';

        final FixReaderBuilder readerBuilder = DeserializerFixReaderBuilder.create(fixModel, globModel,
                HeaderType.TYPE, TrailerType.TYPE);
        final FixReader reader = readerBuilder.createReader(new ByteArrayInputStream(msg)::read);
        final RuntimeException exception = assertThrows(RuntimeException.class, reader::read);
        assertTrue(exception.getMessage().contains("version"),
                "expected a version error but got : " + exception.getMessage());
    }

    /*
    check BodyLength, CheckSum and terminal SOH of a raw message.
     */
    private static void assertConformant(byte[] msg) {
        assertEquals(SOH, msg[msg.length - 1], "message must end with SOH");

        final int checkSumTagAt = lastFieldStart(msg);
        final String checkSumField = new String(msg, checkSumTagAt, msg.length - 1 - checkSumTagAt, StandardCharsets.ISO_8859_1);
        assertTrue(checkSumField.startsWith("10="), "last field must be CheckSum(10) but was " + checkSumField);
        assertEquals(6, checkSumField.length(), "CheckSum must be on exactly 3 digits : " + checkSumField);

        long sum = 0;
        for (int i = 0; i < checkSumTagAt; i++) {
            sum += msg[i] & 0xFF;
        }
        assertEquals(sum % 256, Integer.parseInt(checkSumField.substring(3)),
                "CheckSum must be the sum modulo 256 of all bytes up to the CheckSum field");

        final List<String> fields = splitFields(msg);
        final String bodyLengthField = fields.get(1);
        assertTrue(bodyLengthField.startsWith("9="));
        final int bodyStart = fields.get(0).length() + 1 + bodyLengthField.length() + 1;
        assertEquals(checkSumTagAt - bodyStart, Integer.parseInt(bodyLengthField.substring(2)),
                "BodyLength must count the bytes between BodyLength's SOH and the CheckSum tag");
    }

    private static int lastFieldStart(byte[] msg) {
        for (int i = msg.length - 2; i >= 0; i--) {
            if (msg[i] == SOH) {
                return i + 1;
            }
        }
        throw new IllegalStateException("no SOH found");
    }

    private static List<String> splitFields(byte[] msg) {
        List<String> fields = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < msg.length; i++) {
            if (msg[i] == SOH) {
                fields.add(new String(msg, start, i - start, StandardCharsets.ISO_8859_1));
                start = i + 1;
            }
        }
        return fields;
    }

    private static String findField(List<String> fields, int tag) {
        final String prefix = tag + "=";
        return fields.stream().filter(f -> f.startsWith(prefix))
                .map(f -> f.substring(prefix.length()))
                .findFirst().orElse(null);
    }

    private static int indexOf(byte[] msg, byte[] wanted) {
        for (int i = 0; i <= msg.length - wanted.length; i++) {
            if (Arrays.equals(msg, i, i + wanted.length, wanted, 0, wanted.length)) {
                return i;
            }
        }
        return -1;
    }

    private static byte[] merge(List<byte[]> datas) {
        int len = datas.stream().mapToInt(d -> d.length).sum();
        byte[] merged = new byte[len];
        int at = 0;
        for (byte[] data : datas) {
            System.arraycopy(data, 0, merged, at, data.length);
            at += data.length;
        }
        return merged;
    }
}
