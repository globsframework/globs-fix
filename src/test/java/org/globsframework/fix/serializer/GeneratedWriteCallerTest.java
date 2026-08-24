package org.globsframework.fix.serializer;

import org.globsframework.core.metamodel.GlobType;
import org.globsframework.core.metamodel.impl.DefaultGlobModel;
import org.globsframework.core.model.Glob;
import org.globsframework.core.model.MutableGlob;
import org.globsframework.core.model.caller.FromGlobCallerService;
import org.globsframework.fix.FormatDateTime;
import org.globsframework.fix.HeaderType;
import org.globsframework.fix.TrailerType;
import org.globsframework.fix.deserializer.BasicMsgSeqProvider;
import org.globsframework.fix.dictionary.FixModel;
import org.globsframework.fix.dictionary.xml.FieldFactoryImpl;
import org.globsframework.fix.dictionary.xml.ReadFixDictionary;
import org.globsframework.fix.fix44.app.NewOrderSingleType;
import org.globsframework.fix.fix44.components.InstrumentType;
import org.globsframework.model.generator.AsmCallerGeneratorService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The writers driven by a generated caller rather than by the loop.
 * <p>
 * What has to hold is that the two are the same message : the caller is a way of dispatching, not a second
 * wire format, and a codec cannot be asked to care which one a JVM gave it. So the assertion is the bytes,
 * against the loop over the same writers — and the order, because a caller walks the fields by index unless
 * it is told otherwise and {@code HeaderType} is precisely a type whose declaration order is not the
 * dictionary's (it declares SendingTime before PossDupFlag, where FIX 4.4 has 43 before 52).
 */
public class GeneratedWriteCallerTest {

    /* also before : the suite may be run as a whole with the property already set on the JVM */
    @BeforeEach
    public void setUp() {
        tearDown();
    }

    @AfterEach
    public void tearDown() {
        System.clearProperty("globs.caller.fromGlob");
        FromGlobCallerService.Builder.reset();
    }

    private FixModel loadModel() throws IOException {
        return ReadFixDictionary.parse("FIX.4.4", () ->
                new InputStreamReader(getClass().getClassLoader().getResourceAsStream("FIX44.xml"),
                        StandardCharsets.UTF_8), new FieldFactoryImpl());
    }

    /** A NewOrderSingle with a two-field repeating group, and a header with a PossDupFlag on it. */
    private byte[] render(FixModel fixModel) {
        final SerializerFixWriterBuilder builder = SerializerFixWriterBuilder.create(fixModel,
                new DefaultGlobModel(NewOrderSingleType.TYPE), HeaderType.TYPE, TrailerType.TYPE,
                FormatDateTime.shouldRefreshUTC());
        final byte[][] rendered = new byte[1][];
        final Glob altId = InstrumentType.NoSecurityAltID.TYPE.instantiate()
                .set(InstrumentType.NoSecurityAltID.securityAltID, "ALT1")
                .set(InstrumentType.NoSecurityAltID.securityAltIDSource, "8");
        final Glob altId2 = InstrumentType.NoSecurityAltID.TYPE.instantiate()
                .set(InstrumentType.NoSecurityAltID.securityAltID, "ALT2")
                .set(InstrumentType.NoSecurityAltID.securityAltIDSource, "4");
        final MutableGlob order = NewOrderSingleType.TYPE.instantiate()
                .set(NewOrderSingleType.clOrdID, "order-1")
                .set(NewOrderSingleType.symbol, "ACME.PA")
                .set(NewOrderSingleType.securityType, "CS")
                .set(NewOrderSingleType.securityAltIDs, new Glob[]{altId, altId2});
        final MutableGlob header = HeaderType.create("SENDER", "TARGET")
                .set(HeaderType.possDupFlag, true)
                .set(HeaderType.sendingTime, "20260824-10:15:30.000");
        builder.createWriter((data, offset, length) ->
                        rendered[0] = Arrays.copyOfRange(data, offset, offset + length),
                new BasicMsgSeqProvider())
                .write(header, order, TrailerType.TYPE.instantiate(), false);
        return rendered[0];
    }

    private void installGenerator() {
        System.setProperty("globs.caller.fromGlob", AsmCallerGeneratorService.class.getName());
        FromGlobCallerService.Builder.reset();
    }

    @Test
    public void theGeneratedCallerWritesExactlyWhatTheLoopWrites() throws IOException {
        final FixModel fixModel = loadModel();
        final byte[] byTheLoop = render(fixModel);

        installGenerator();
        final byte[] byTheCaller = render(fixModel);

        assertArrayEquals(byTheLoop, byTheCaller,
                new String(byTheLoop, StandardCharsets.ISO_8859_1).replace((char) 1, '|') + "\n" +
                new String(byTheCaller, StandardCharsets.ISO_8859_1).replace((char) 1, '|'));
    }

    /**
     * ... and that is the dictionary's order, not the field index order the caller would walk on its own.
     * PossDupFlag(43) before SendingTime(52) is the pair HeaderType declares the other way round.
     */
    @Test
    public void theOrderOnTheWireIsStillTheDictionarysUnderTheCaller() throws IOException {
        installGenerator();
        final List<Integer> tags = Arrays.stream(new String(render(loadModel()), StandardCharsets.ISO_8859_1)
                        .split(String.valueOf((char) 1)))
                .map(f -> Integer.valueOf(f.substring(0, f.indexOf('='))))
                .toList();

        assertEquals(List.of(8, 9, 35, 49, 56, 34, 43, 52, 11, 55, 454, 455, 456, 455, 456, 167, 10), tags);
    }

    /** The two arms of the test above are the two arms of MessageFieldWrite : neither may go missing. */
    @Test
    public void theCallerIsReallyWhatRunsOnceTheServiceIsInstalled() {
        final GlobType type = NewOrderSingleType.TYPE;
        assertFalse(new MessageFieldWrite(type, Map.of()).isGenerated(), "nothing installed : the loop");

        installGenerator();
        assertTrue(new MessageFieldWrite(type, Map.of()).isGenerated(),
                "the service generates over core's DefaultGlob");
    }
}
