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

import static org.junit.jupiter.api.Assertions.*;

/**
 * ExecInst (tag 18) is a MULTIPLEVALUESTRING : a StringArrayField serialized as a single
 * space separated tag value.
 */
class MultipleValueStringTest {

    @Test
    void multipleValueStringIsMappedToAStringArrayField() throws IOException {
        final GlobType newOrderSingle = Arrays.stream(FixModelToGlobType.toType(readDictionary()).messages())
                .filter(message -> message.getAnnotation(FixMessageType.UNIQUE_KEY)
                        .get(FixMessageType.name).equals("NewOrderSingle"))
                .findFirst()
                .orElseThrow();
        assertInstanceOf(StringArrayField.class, newOrderSingle.getField("ExecInst"));
    }

    @Test
    void readWriteMultipleValues() throws IOException {
        final String[] execInst = {"1", "G"};
        final String message = writeAsString(NewOrderSingleType.create("order1", execInst));

        assertTrue(message.contains("18=1 G"), message);
        assertArrayEquals(execInst, readBack(message).get(NewOrderSingleType.execInst));
    }

    @Test
    void readWriteASingleValue() throws IOException {
        final String message = writeAsString(NewOrderSingleType.create("order1", new String[]{"G"}));

        assertTrue(message.contains("18=G"), message);
        assertArrayEquals(new String[]{"G"}, readBack(message).get(NewOrderSingleType.execInst));
    }

    @Test
    void emptyValuesAreDroppedOnWrite() throws IOException {
        final String message = writeAsString(NewOrderSingleType.create("order1", new String[]{"1", "", "G"}));

        assertTrue(message.contains("18=1 G"), message);
        assertArrayEquals(new String[]{"1", "G"}, readBack(message).get(NewOrderSingleType.execInst));
    }

    @Test
    void anUnsetFieldIsNotWritten() throws IOException {
        final String message = writeAsString(NewOrderSingleType.TYPE.instantiate()
                .set(NewOrderSingleType.clOrdID, "order1"));

        assertFalse(message.contains("18="), message);
        final Glob read = readBack(message);
        assertFalse(read.isSet(NewOrderSingleType.execInst));
        assertNull(read.get(NewOrderSingleType.execInst));
    }

    @Test
    void anEmptyArrayIsNotWritten() throws IOException {
        final String message = writeAsString(NewOrderSingleType.create("order1", new String[0]));

        assertFalse(message.contains("18="), message);
        assertFalse(readBack(message).isSet(NewOrderSingleType.execInst));
    }

    @Test
    void anArrayOfOnlyEmptyValuesIsNotWritten() throws IOException {
        final String message = writeAsString(NewOrderSingleType.create("order1", new String[]{"", ""}));

        assertFalse(message.contains("18="), message);
        final Glob read = readBack(message);
        assertFalse(read.isSet(NewOrderSingleType.execInst));
    }

    private FixModel readDictionary() throws IOException {
        return ReadFixDictionary.parse("fix44", () ->
                new InputStreamReader(getClass().getClassLoader().getResourceAsStream("FIX44.xml"),
                        StandardCharsets.UTF_8), new FieldFactoryImpl());
    }

    private GlobModel globModel() {
        return new DefaultGlobModel(NewOrderSingleType.TYPE);
    }

    private String writeAsString(MutableGlob message) throws IOException {
        final SerializerFixWriterBuilder builder = SerializerFixWriterBuilder.create(readDictionary(), globModel(),
                HeaderType.TYPE, TrailerType.TYPE, FormatDateTime.shouldRefreshUTC());
        List<byte[]> datas = new ArrayList<>();
        final FixWriter writer = builder.createWriter(
                (data, offset, length) -> datas.add(Arrays.copyOfRange(data, offset, offset + length)),
                new BasicMsgSeqProvider());

        writer.write(HeaderType.create("AA", "BB"), message, null, false);

        assertEquals(1, datas.size());
        return new String(datas.getFirst(), StandardCharsets.ISO_8859_1);
    }

    private Glob readBack(String message) throws IOException {
        final FixReaderBuilder builder = DeserializerFixReaderBuilder.create(readDictionary(), globModel(),
                HeaderType.TYPE, TrailerType.TYPE);
        final ByteArrayInputStream input = new ByteArrayInputStream(message.getBytes(StandardCharsets.ISO_8859_1));
        final FixMessageValue read = builder.createReader(input::read).read();
        assertNotNull(read);
        assertNull(read.decodeError());
        return read.message();
    }

    public static class NewOrderSingleType {
        public static final GlobType TYPE;

        public static final StringField clOrdID;

        public static final StringArrayField execInst;

        public static MutableGlob create(String clOrdId, String[] execInsts) {
            return TYPE.instantiate()
                    .set(clOrdID, clOrdId)
                    .set(execInst, execInsts);
        }

        static {
            final GlobTypeBuilder builder = GlobTypeBuilderFactory.create("NewOrderSingle");
            builder.addAnnotation(FixMessageType.create("NewOrderSingle"));
            clOrdID = builder.declareStringField("clOrdID", FixFieldType.create("ClOrdID"));
            execInst = builder.declareStringArrayField("execInst", FixFieldType.create("ExecInst"));
            TYPE = builder.build();
        }
    }
}
