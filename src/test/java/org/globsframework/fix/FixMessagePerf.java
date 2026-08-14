package org.globsframework.fix;

import org.globsframework.core.metamodel.GlobModel;
import org.globsframework.core.metamodel.GlobType;
import org.globsframework.core.metamodel.GlobTypeBuilder;
import org.globsframework.core.metamodel.GlobTypeBuilderFactory;
import org.globsframework.core.metamodel.fields.BooleanField;
import org.globsframework.core.metamodel.fields.DateTimeField;
import org.globsframework.core.metamodel.fields.IntegerField;
import org.globsframework.core.metamodel.fields.StringArrayField;
import org.globsframework.core.metamodel.fields.StringField;
import org.globsframework.core.metamodel.impl.DefaultGlobModel;
import org.globsframework.core.model.MutableGlob;
import org.globsframework.fix.deserializer.BasicMsgSeqProvider;
import org.globsframework.fix.deserializer.ByteReader;
import org.globsframework.fix.deserializer.DeserializerFixReaderBuilder;
import org.globsframework.fix.deserializer.FixReader;
import org.globsframework.fix.deserializer.FixReaderBuilder;
import org.globsframework.fix.dictionary.FixModel;
import org.globsframework.fix.dictionary.model.FixFieldType;
import org.globsframework.fix.dictionary.model.FixMessageType;
import org.globsframework.fix.dictionary.xml.FieldFactoryImpl;
import org.globsframework.fix.dictionary.xml.ReadFixDictionary;
import org.globsframework.fix.serializer.FixWriter;
import org.globsframework.fix.serializer.SerializerFixWriterBuilder;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.util.Arrays;

/**
 * One NewOrderSingle written, and one read back, under core's DefaultGlob and the two ASM flavours of
 * globs-generate — the end-to-end measurement this module had none of (`UtilsBenchmark` only covers the byte
 * utilities).
 * <p>
 * What it is here to answer: what the per-field dispatch costs. Writing walks a `FieldWrite[]` in
 * `MessageFieldWrite.writeAt`, one call site for every writer class in the process; reading dispatches per tag
 * and ends on `DirectFieldReader.read`, one call site for every reader class. That is the shape globs-grpc and
 * globs-bin-serialisation removed with a generated caller, worth +17 % to +51 % there. The flavour matters
 * because each leaf also reaches its value through a typed accessor, whose implementation is what
 * `globs.builder` changes.
 * <p>
 * The message carries 19 fields spanning five leaf kinds (String, Integer, Boolean, DateTime, StringArray) —
 * with a single kind the call sites would be monomorphic and the numbers would say nothing.
 * <p>
 * Run :
 * <pre>
 * mvn -o test-compile dependency:build-classpath -Dmdep.outputFile=/tmp/cp.txt
 * java -cp target/classes:target/test-classes:$(cat /tmp/cp.txt) org.openjdk.jmh.Main FixMessagePerf -p flavour=OBJECT
 * </pre>
 */
@BenchmarkMode(Mode.Throughput)
@Warmup(iterations = 3, time = 3)
@Measurement(iterations = 3, time = 3)
@Fork(2)
@State(Scope.Thread)
public class FixMessagePerf {

    @Param({"DEFAULT", "OBJECT", "PRIMITIVE"})
    public String flavour;

    private FixWriter writer;
    private MutableGlob header;
    private MutableGlob message;
    private int lastWritten;
    private FixReader reader;

    @Setup
    public void setup() throws IOException {
        final FixModel fixModel = readDictionary();
        final Types types = GlobFlavour.valueOf(flavour).build(() -> Types.declare(flavour));
        final GlobModel globModel = new DefaultGlobModel(types.order);

        final SerializerFixWriterBuilder writerBuilder = SerializerFixWriterBuilder.create(fixModel, globModel,
                types.header, types.trailer, FormatDateTime.shouldRefreshUTC());
        message = types.newOrder();
        header = types.newHeader();
        writer = writerBuilder.createWriter((data, offset, length) -> lastWritten = length,
                new BasicMsgSeqProvider());

        // one rendered message, replayed for the read benchmark : the reader is built once, as in a session,
        // so its 10 KB buffer is not what gets measured
        final byte[][] rendered = new byte[1][];
        writerBuilder.createWriter((data, offset, length) ->
                                rendered[0] = Arrays.copyOfRange(data, offset, offset + length),
                        new BasicMsgSeqProvider())
                .write(types.newHeader(), message, null, false);
        final FixReaderBuilder readerBuilder = DeserializerFixReaderBuilder.create(fixModel, globModel,
                types.header, types.trailer);
        reader = readerBuilder.createReader(new CyclicByteReader(rendered[0]));
    }

    /** one pass = one message serialized, header included */
    @Benchmark
    public int write() {
        writer.write(header, message, null, false);
        return lastWritten;
    }

    /** one pass = one message parsed back into a Glob, from a stream that repeats it */
    @Benchmark
    public void read(Blackhole blackhole) {
        blackhole.consume(reader.read().message());
    }

    /** The same message for ever : messages are self-framing, so a cyclic stream parses like a session. */
    private static final class CyclicByteReader implements ByteReader {
        private final byte[] data;
        private int at;

        private CyclicByteReader(byte[] data) {
            this.data = data;
        }

        public int read(byte[] buf, int offset, int len) {
            final int read = Math.min(len, data.length - at);
            System.arraycopy(data, at, buf, offset, read);
            at += read;
            if (at == data.length) {
                at = 0;
            }
            return read;
        }
    }

    static FixModel readDictionary() throws IOException {
        return ReadFixDictionary.parse("fix44", () ->
                        new InputStreamReader(FixMessagePerf.class.getClassLoader().getResourceAsStream("FIX44.xml"),
                                StandardCharsets.UTF_8),
                new FieldFactoryImpl());
    }

    /**
     * Header, trailer and message types, declared together so that all three are built under the flavour being
     * measured — a static fixture would pin them to whatever built first.
     * <p>
     * Symbol, SecurityID, SecurityIDSource and OrderQty belong to the Instrument and OrderQtyData
     * *components* in FIX44.xml; components being flattened, a message declares them inline.
     */
    record Types(GlobType header, GlobType trailer, GlobType order,
                         StringField senderCompID, StringField targetCompID,
                         StringField clOrdID, StringField account, StringField symbol, StringField side,
                         StringField ordType, StringField timeInForce, StringField handlInst,
                         StringField currency, StringField exDestination, StringField securityID,
                         StringField securityIDSource, StringField text, StringField price,
                         IntegerField orderQty, IntegerField minQty, IntegerField maxFloor,
                         DateTimeField transactTime, StringArrayField execInst, BooleanField solicitedFlag) {

        static Types declare(String tag) {
            final GlobTypeBuilder headerBuilder = GlobTypeBuilderFactory.create("Header" + tag);
            final StringField senderCompID = headerBuilder.declareStringField("senderCompID", FixFieldType.create("SenderCompID"));
            final StringField targetCompID = headerBuilder.declareStringField("targetCompID", FixFieldType.create("TargetCompID"));
            headerBuilder.declareStringField("msgType", FixFieldType.create("MsgType"));
            headerBuilder.declareIntegerField("msgSeqNum", FixFieldType.create("MsgSeqNum"));
            headerBuilder.declareStringField("sendingTime", FixFieldType.create("SendingTime"));
            headerBuilder.declareStringField("origSendingTime", FixFieldType.create("OrigSendingTime"));
            headerBuilder.declareBooleanField("possDupFlag", FixFieldType.create("PossDupFlag"));
            final GlobType header = headerBuilder.build();

            final GlobTypeBuilder trailerBuilder = GlobTypeBuilderFactory.create("Trailer" + tag);
            trailerBuilder.declareStringField("signature", FixFieldType.create("Signature"));
            trailerBuilder.declareIntegerField("checkSum", FixFieldType.create("CheckSum"));
            final GlobType trailer = trailerBuilder.build();

            final GlobTypeBuilder builder = GlobTypeBuilderFactory.create("NewOrderSingle" + tag);
            builder.addAnnotation(FixMessageType.create("NewOrderSingle"));
            final Types types = new Types(header, trailer, null,
                    senderCompID, targetCompID,
                    builder.declareStringField("clOrdID", FixFieldType.create("ClOrdID")),
                    builder.declareStringField("account", FixFieldType.create("Account")),
                    builder.declareStringField("symbol", FixFieldType.create("Symbol")),
                    builder.declareStringField("side", FixFieldType.create("Side")),
                    builder.declareStringField("ordType", FixFieldType.create("OrdType")),
                    builder.declareStringField("timeInForce", FixFieldType.create("TimeInForce")),
                    builder.declareStringField("handlInst", FixFieldType.create("HandlInst")),
                    builder.declareStringField("currency", FixFieldType.create("Currency")),
                    builder.declareStringField("exDestination", FixFieldType.create("ExDestination")),
                    builder.declareStringField("securityID", FixFieldType.create("SecurityID")),
                    builder.declareStringField("securityIDSource", FixFieldType.create("SecurityIDSource")),
                    builder.declareStringField("text", FixFieldType.create("Text")),
                    builder.declareStringField("price", FixFieldType.create("Price")),
                    builder.declareIntegerField("orderQty", FixFieldType.create("OrderQty")),
                    builder.declareIntegerField("minQty", FixFieldType.create("MinQty")),
                    builder.declareIntegerField("maxFloor", FixFieldType.create("MaxFloor")),
                    builder.declareDateTimeField("transactTime", FixFieldType.create("TransactTime")),
                    builder.declareStringArrayField("execInst", FixFieldType.create("ExecInst")),
                    builder.declareBooleanField("solicitedFlag", FixFieldType.create("SolicitedFlag")));
            return types.withOrder(builder.build());
        }

        private Types withOrder(GlobType order) {
            return new Types(header, trailer, order, senderCompID, targetCompID, clOrdID, account, symbol, side,
                    ordType, timeInForce, handlInst, currency, exDestination, securityID, securityIDSource,
                    text, price, orderQty, minQty, maxFloor, transactTime, execInst, solicitedFlag);
        }

        MutableGlob newHeader() {
            return header.instantiate()
                    .set(senderCompID, "SENDER")
                    .set(targetCompID, "TARGET");
        }

        MutableGlob newOrder() {
            return order.instantiate()
                    .set(clOrdID, "order-4711")
                    .set(account, "ACC-12")
                    .set(symbol, "ACME.PA")
                    .set(side, "1")
                    .set(ordType, "2")
                    .set(timeInForce, "0")
                    .set(handlInst, "1")
                    .set(currency, "EUR")
                    .set(exDestination, "XPAR")
                    .set(securityID, "FR0000120404")
                    .set(securityIDSource, "4")
                    .set(text, "a comment on the order")
                    .set(price, "123.45")
                    .set(orderQty, 2500)
                    .set(minQty, 100)
                    .set(maxFloor, 500)
                    .set(transactTime, ZonedDateTime.parse("2026-08-14T10:15:30Z"))
                    .set(execInst, new String[]{"1", "G"})
                    .set(solicitedFlag, true);
        }
    }

    public static void main(String[] args) throws RunnerException {
        new Runner(new OptionsBuilder()
                .include(FixMessagePerf.class.getSimpleName())
                .build())
                .run();
    }
}
