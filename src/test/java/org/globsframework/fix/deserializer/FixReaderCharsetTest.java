package org.globsframework.fix.deserializer;

import org.globsframework.core.metamodel.GlobModel;
import org.globsframework.core.metamodel.GlobType;
import org.globsframework.core.metamodel.GlobTypeBuilder;
import org.globsframework.core.metamodel.GlobTypeBuilderFactory;
import org.globsframework.core.metamodel.fields.StringArrayField;
import org.globsframework.core.metamodel.fields.StringField;
import org.globsframework.core.metamodel.impl.DefaultGlobModel;
import org.globsframework.core.model.Glob;
import org.globsframework.core.model.MutableGlob;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The wire is ISO-8859-1. The readers used to decode with the platform default charset, which is UTF-8
 * since JDK 18 : every byte over 0x7F came back as U+FFFD.
 */
class FixReaderCharsetTest {
    private static final String LATIN1 = "Société Générale, 100% sûr, ±5°, Ñ";

    @Test
    void aStringFieldKeepsItsLatin1Bytes() throws IOException {
        final MutableGlob message = OrderType.TYPE.instantiate()
                .set(OrderType.clOrdID, "order1")
                .set(OrderType.text, LATIN1);

        final String written = write(message);

        assertTrue(written.contains("58=" + LATIN1), written);
        assertEquals(LATIN1, readBack(written).get(OrderType.text));
    }

    @Test
    void aMultipleValueStringKeepsItsLatin1Bytes() throws IOException {
        final String[] values = {"é", "ü", "ç"};
        final MutableGlob message = OrderType.TYPE.instantiate()
                .set(OrderType.clOrdID, "order1")
                .set(OrderType.execInst, values);

        final String written = write(message);

        assertTrue(written.contains("18=é ü ç"), written);
        assertArrayEquals(values, readBack(written).get(OrderType.execInst));
    }

    /**
     * Each byte over 0x7F is one character : a value the reader gives back must have as many characters
     * as the tag had bytes on the wire, whatever the platform charset.
     */
    @Test
    void everyByteOfTheWireIsOneCharacter() throws IOException {
        final StringBuilder allBytes = new StringBuilder();
        for (int b = 0x20; b < 0x100; b++) {
            if (b != '=' && b != 1) { // '=' and SOH are the field delimiters
                allBytes.append((char) b);
            }
        }
        final MutableGlob message = OrderType.TYPE.instantiate()
                .set(OrderType.clOrdID, "order1")
                .set(OrderType.text, allBytes.toString());

        final String read = readBack(write(message)).get(OrderType.text);

        assertEquals(allBytes.length(), read.length());
        assertEquals(allBytes.toString(), read);
    }

    private FixModel readDictionary() throws IOException {
        return ReadFixDictionary.parse("fix44", () ->
                new InputStreamReader(getClass().getClassLoader().getResourceAsStream("FIX44.xml"),
                        StandardCharsets.UTF_8), new FieldFactoryImpl());
    }

    private GlobModel globModel() {
        return new DefaultGlobModel(OrderType.TYPE);
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

    private Glob readBack(String message) throws IOException {
        final ByteArrayInputStream input = new ByteArrayInputStream(message.getBytes(StandardCharsets.ISO_8859_1));
        final FixMessageValue read = DeserializerFixReaderBuilder
                .create(readDictionary(), globModel(), HeaderType.TYPE, TrailerType.TYPE)
                .createReader(input::read).read();
        assertNull(read.decodeError());
        return read.message();
    }

    public static class OrderType {
        public static final GlobType TYPE;
        public static final StringField clOrdID;
        public static final StringField text;
        public static final StringArrayField execInst;

        static {
            final GlobTypeBuilder builder = GlobTypeBuilderFactory.create("NewOrderSingle");
            builder.addAnnotation(FixMessageType.create("NewOrderSingle"));
            clOrdID = builder.declareStringField("clOrdID", FixFieldType.create("ClOrdID"));
            text = builder.declareStringField("text", FixFieldType.create("Text"));
            execInst = builder.declareStringArrayField("execInst", FixFieldType.create("ExecInst"));
            TYPE = builder.build();
        }
    }
}
