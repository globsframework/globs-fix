# Globs FIX

A FIX 4.4 engine where messages are [Glob](https://globsframework.org) instances instead of generated
classes. The binding between a `GlobType` and the FIX wire format is annotation-driven and resolved once at
startup into flat lookup tables; the per-message path then does no reflection and no allocation.

- The dictionary is a QuickFIX-style XML file, read into a `FixModel`
- A message type is an ordinary `GlobType` carrying `FixMessageType` / `FixFieldType` annotations — matching
  is by FIX name, so the Java field names are free
- A full session layer (logon/logout, sequence numbers, gap fill, resend, heartbeat, test request)

## Requirements

- Java 22 or higher
- `org.globsframework:globs` 5.13-SNAPSHOT, plus `globs-xml` (dictionary parsing) and `globs-gson`

## Installation

```xml
<dependency>
    <groupId>org.globsframework</groupId>
    <artifactId>globs-fix</artifactId>
    <version>5.2.0</version>
</dependency>
```

## Binding a GlobType to FIX

Everything is matched on the FIX name found in the dictionary:

| Annotation | Placed on | Meaning |
| --- | --- | --- |
| `FixMessageType.create("NewOrderSingle")` | a `GlobType` | `<message name="NewOrderSingle">` |
| `FixFieldType.create("ClOrdID")` | a `Field` | `<field name="ClOrdID">`, which supplies the tag number |
| `FixGroupType.create("NoSecurityAltID")` | the **target** type of a `GlobArrayField` | a repeating group; the value is the group's count field |
| `FixComponentType.create("Instrument")` | a `GlobType` | a component — components are *flattened*, so a message may declare `Symbol` inline instead of nesting an `Instrument` glob |

```java
public class NewOrderSingleType {
    public static final GlobType TYPE;
    public static final StringField clOrdID;
    public static final StringField symbol;
    public static final GlobArrayField<InstrumentType.NoSecurityAltID> securityAltIDs;

    static {
        GlobTypeBuilder builder = GlobTypeBuilderFactory.create("NewOrderSingle");
        builder.addAnnotation(FixMessageType.create("NewOrderSingle"));
        clOrdID = builder.declareStringField("clOrdID", FixFieldType.create("ClOrdID"));
        symbol = builder.declareStringField("symbol", FixFieldType.create("Symbol"));
        securityAltIDs = builder.declareGlobArrayField("securityAltIDs", () -> InstrumentType.NoSecurityAltID.TYPE);
        TYPE = builder.build();
    }
}
```

Tags present in the dictionary but absent from the `GlobType` are skipped on read and logged at debug on
write, so a type only needs to declare the fields the application cares about.

`src/test/java/org/globsframework/fix/fix44/**`, `HeaderType` and `TrailerType` are the reference examples of
hand-written bound types. `FixModelToGlobType` does the opposite when no hand-written type is wanted: it
generates `GlobType`s straight from a `FixModel`.

## Writing and reading a message

```java
// 1. the dictionary — parsed with four SAX passes, hence the Supplier<Reader>
FixModel fixModel = ReadFixDictionary.parse("FIX.4.4",
        () -> new InputStreamReader(loader.getResourceAsStream("FIX44.xml"), StandardCharsets.UTF_8),
        new FieldFactoryImpl());

// 2. the application messages; the admin ones (Logon, Heartbeat, ...) are added automatically
GlobModel globModel = new DefaultGlobModel(NewOrderSingleType.TYPE, ExecutionReportType.TYPE);

// 3. a writer publishing the finished bytes
SerializerFixWriterBuilder writerBuilder = SerializerFixWriterBuilder.create(fixModel, globModel,
        HeaderType.TYPE, TrailerType.TYPE, FormatDateTime.autoRefreshUTC(scheduler));
FixWriter writer = writerBuilder.createWriter(
        (data, offset, length) -> socket.write(data, offset, length), new BasicMsgSeqProvider());

writer.write(HeaderType.create("SENDER", "TARGET"),
        NewOrderSingleType.TYPE.instantiate()
                .set(NewOrderSingleType.clOrdID, "order-1")
                .set(NewOrderSingleType.symbol, "ACME.PA"),
        TrailerType.TYPE.instantiate(), false);

// 4. a reader streaming from a ByteReader
FixReader reader = DeserializerFixReaderBuilder.create(fixModel, globModel, HeaderType.TYPE, TrailerType.TYPE)
        .createReader(inputStream::read);
FixMessageValue read = reader.read();
read.header().get(HeaderType.senderCompID);
read.message().get(NewOrderSingleType.clOrdID);
```

A message that frames and checksums correctly but whose body cannot be decoded comes back as a
`FixMessageValue` carrying a `DecodeError(sessionRejectReason, text)` — the session layer rejects it and
consumes the sequence number rather than dropping the connection.

Fields are written in **dictionary order**: a field that is not set writes nothing, and a group with no entry
is omitted entirely, count included. `MsgSeqNum` and `SendingTime` are filled in only when unset.

## FIX type ⇄ glob field

| FIX type | glob field |
| --- | --- |
| `INT`, `NUMINGROUP`, `LENGTH`, `SEQNUM` | `IntegerField` |
| `BOOLEAN` | `BooleanField` |
| `FLOAT` | `DoubleField` |
| `MULTIPLEVALUESTRING` | `StringArrayField`, on the wire as one space-separated value (`18=1 G`) |
| everything else | `StringField` |

Everything is ISO-8859-1 on the wire; the field separator (SOH) is `0x1`.

## Session layer

```java
HeaderDesc headerDesc = HeaderDesc.create(HeaderType.TYPE);
SerializerProvider provider = new SingleSerializerProvider(readerBuilder, writerBuilder, headerDesc);

FixServer fixServer = new FixServer("0.0.0.0", 5456,
        new FixConnectionFactory(publish, executor, scheduler,
                userLogonSessionFactory,                       // the application side
                (sender, target) -> new NoCacheDataAdapt(),    // resend cache and seqnum state
                provider, headerDesc));
executor.submit(fixServer::acceptAsAcceptor);   // or acceptAsInitiator(sender, target)
```

`FixSessionImpl` implements the FIX 4.4 session rules as a state machine: logon/logout handshake,
sequence-number checking, gap detection and gap fill, resend requests, `PossDupFlag`/`OrigSendingTime`
validation, heartbeats and test requests. Application code plugs in through `UserLogonSessionFactory` →
`UserSession` → `AppMessageReceiver`; replay storage is behind `FixMessageRepository`
(`InMemoryCacheDataAdapt` or `NoCacheDataAdapt`).

A header `GlobType` must declare the seven session-critical fields (`SenderCompID`, `TargetCompID`,
`MsgType`, `MsgSeqNum`, `PossDupFlag`, `SendingTime`, `OrigSendingTime`) — `HeaderDesc.create` throws
otherwise.

Use `FormatDateTime.autoRefreshUTC(scheduler)` in production (the date prefix and the second/millisecond byte
pairs are pre-rendered, so a timestamp costs no allocation) and `FormatDateTime.shouldRefreshUTC()` in tests.

## Performance

Writing goes through a `FromGlobCaller` when one can be generated: one monomorphic call site per field
instead of a loop over a `FieldWrite[]`. Turning it on for plain `DefaultGlob`s is a JVM property and does
not require generating any Glob class:

```
-Dglobs.caller.fromGlob=org.globsframework.model.generator.AsmCallerGeneratorService
```

Measured on a NewOrderSingle of 19 fields (JMH, `FixMessagePerf`): write goes from 2.71 M to 3.55 M ops/s
(+31 %). The read side was tried the same way and loses — see `CLAUDE.md`, which records the numbers and why.

## Building

```bash
mvn test                                        # full suite (JUnit 5)
mvn test -Dtest=FixSessionSpecTest              # a single class
mvn -q test-compile                             # compile only
```

Surefire only picks up `*Test.java`; the other classes under `src/test` are fakes, bound example types, or
`main()` harnesses (`Server`, `Client`, `SingleOrderClient`) for manual runs.

## License

Apache License 2.0 — see <https://www.apache.org/licenses/LICENSE-2.0.txt>.

## Links

- [Globs Framework](https://globsframework.org)
- [GitHub repository](https://github.com/globsframework/globs-fix)
