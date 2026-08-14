package org.globsframework.fix;

import org.globsframework.core.metamodel.impl.DefaultGlobModel;
import org.globsframework.core.model.Glob;
import org.globsframework.fix.deserializer.BasicMsgSeqProvider;
import org.globsframework.fix.deserializer.DeserializerFixReaderBuilder;
import org.globsframework.fix.deserializer.FixMessageValue;
import org.globsframework.fix.dictionary.FixModel;
import org.globsframework.fix.serializer.SerializerFixWriterBuilder;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What {@link FixMessagePerf} measures has to be a whole message : a field the dictionary does not place in
 * NewOrderSingle would simply not be written, and the benchmark would quietly measure a shorter message. This
 * pins the fixture — every tag out, and the values back in.
 */
class FixMessagePerfFixtureTest {

    @Test
    void theBenchmarkMessageCarriesAllOfItsFields() throws IOException {
        final FixModel fixModel = FixMessagePerf.readDictionary();
        final FixMessagePerf.Types types = FixMessagePerf.Types.declare("Fixture");
        final DefaultGlobModel globModel = new DefaultGlobModel(types.order());

        final SerializerFixWriterBuilder writerBuilder = SerializerFixWriterBuilder.create(fixModel, globModel,
                types.header(), types.trailer(), FormatDateTime.shouldRefreshUTC());
        final byte[][] rendered = new byte[1][];
        writerBuilder.createWriter((data, offset, length) ->
                                rendered[0] = Arrays.copyOfRange(data, offset, offset + length),
                        new BasicMsgSeqProvider())
                .write(types.newHeader(), types.newOrder(), null, false);

        final String message = new String(rendered[0], StandardCharsets.ISO_8859_1);
        // one assertion per leaf kind : String, Integer, Boolean, DateTime, StringArray
        for (String tag : new String[]{"11=order-4711", "55=ACME.PA", "38=2500", "377=Y", "18=1 G",
                "60=20260814-10:15:30", "44=123.45", "110=100", "111=500", "58=a comment on the order"}) {
            assertTrue(message.contains(tag), tag + " missing from " + message);
        }

        final FixMessageValue read = DeserializerFixReaderBuilder
                .create(fixModel, globModel, types.header(), types.trailer())
                .createReader(new ByteArrayInputStream(rendered[0])::read)
                .read();
        assertNull(read.decodeError());
        final Glob order = read.message();
        assertEquals("order-4711", order.get(types.clOrdID()));
        assertEquals(2500, order.get(types.orderQty()).intValue());
        assertTrue(order.get(types.solicitedFlag()));
        assertArrayEquals(new String[]{"1", "G"}, order.get(types.execInst()));
    }
}
