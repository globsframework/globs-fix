# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & test

Maven, Java 22 source/target (built with a newer JDK). Dependencies (`globs`, `globs-xml`, `globs-gson`)
come from the GitHub Packages repo `https://maven.pkg.github.com/globsframework/*`, which may require
credentials in `~/.m2/settings.xml`.

```bash
mvn test                                        # full test run
mvn test -Dtest=FixSessionSpecTest              # one test class
mvn test -Dtest=FixSessionSpecTest#testLogon    # one test method
mvn -q test-compile                             # compile only (fast feedback)
mvn package
```

Surefire only picks up `**/*Test.java` / `**/*Tests.java`. Classes in `src/test` without that suffix are
either fakes/helpers (`TestUserSession`, `CompletableByteReader`, `SingleSerializerProvider`,
`HeaderType`, `TrailerType`, the `fix44/**` glob types) or `main()` harnesses for manual runs
(`Server`, `Client`, `DirectClientServer`, `SingleOrderClient`, `UtilsBenchmark`).

Tests use JUnit 5 with hand-written fakes. There is no mocking framework — do not introduce one.

### Benchmarks

`UtilsBenchmark` covers the byte utilities; **`FixMessagePerf` is the end-to-end one**: one NewOrderSingle of
19 fields spanning five leaf kinds (String, Integer, Boolean, DateTime, StringArray), written and read back,
under core's `DefaultGlob` and both ASM flavours of `globs-generate` (test-scoped, `5.3-SNAPSHOT`, needs an
`mvn install` in `../globs-generate`; the flavour is a `@Param`, so JMH forks one JVM per flavour). The reader
is built once and fed a *cyclic* `ByteReader` replaying the same rendered message, as a session would — FIX
messages are self-framing — so its 10 KB buffer is not what gets measured.

```bash
mvn -o test-compile dependency:build-classpath -Dmdep.outputFile=/tmp/cp.txt
java -cp target/classes:target/test-classes:$(cat /tmp/cp.txt) org.openjdk.jmh.Main FixMessagePerf -p flavour=OBJECT
```

`FixMessagePerfFixtureTest` is the guard: a field the dictionary does not place in NewOrderSingle would simply
not be written and the benchmark would quietly measure a shorter message, so the test asserts every tag is on
the wire and comes back. Run it after touching the fixture.

Measured (3 forks, ops/s; one machine, one session — compare columns, not absolute values against an older
run):

| | DEFAULT | OBJECT | PRIMITIVE |
| --- | --- | --- | --- |
| `write` | **2.84 M** | 2.72 M | 2.64 M |
| `read` | **1.46 M** | 1.37 M | 1.31 M |
| `read`, before the set accessors | 1.21 M | 1.20 M | 1.14 M |

The last row is what the readers cost while they still called `data.set(field, value)`: giving every
`FieldReader` the field's `GlobSetAccessor` (resolved once in `DeserializerFixReaderBuilder`, `setNative` for
int and boolean) is worth **+21 % on DEFAULT and +14 to +15 % on the generated flavours**, and leaves `write`
untouched — it already went through `GlobGetAccessor`. The readers keep the `Field` too, but only for
`isSet`, which the set accessor does not cover.

Those three columns are the loop: `MessageFieldWrite` walking a `FieldWrite[]`, one call site for every
writer class in the process, and reading ending on `DirectFieldReader.read`, one call site for every reader
class. Generating the Globs was a small loss there (−4 to −7 % on write, −6 to −10 % on read), one accessor
class per field putting yet more receivers on the same megamorphic sites — the state globs-grpc was in
before it drove its leaves through a generated caller.

### The write side through a caller

`MessageFieldWrite` now asks core for a `FromGlobCaller` and only loops when it gets none. Measured the same
way (4 forks; `read` is untouched by any of this and stays at 1.40 M):

| `write` | DEFAULT | OBJECT | PRIMITIVE |
| --- | --- | --- | --- |
| the loop | 2.71 M | 2.72 M | 2.64 M |
| **through the caller** | **3.55 M** | **3.72 M** | **3.62 M** |
| | +31 % | +37 % | +37 % |

On the generated flavours the caller comes from the type's own factory and costs nothing to turn on — which
is what reverses the "generating is a loss" column above, for `write`. On DEFAULT it takes the service, and
that is the configuration to prefer: **3.55 M without generating a single Glob class**, 5 % behind the best
generated flavour and without the inlining damage a generated `doGet`/`doSet` does to application code
(globs-generate's CLAUDE.md measures that separately).

```bash
java -cp ... org.openjdk.jmh.Main FixMessagePerf -p flavour=DEFAULT \
     -jvmArgsAppend "-Dglobs.caller.fromGlob=org.globsframework.model.generator.AsmCallerGeneratorService"
```

Two things had to happen first, and both are worth knowing before touching this code. `FieldWrite.writeAt`
used to return the new buffer index; it returns **void** and advances `WriteBuffer.at` in place, which is
the shape `FromGlobFunction` has. That cost −2 to −3 % on its own (2.76 M → 2.71 M) and is the deposit, not
a gain. And core's caller SPI had to learn an **order**: it walked the fields by index, where FIX needs the
dictionary's — see `MessageFieldWrite`.

### The read side does not want one — measured

The reader is the slower half by far (1.39 M against 3.55 M) and is the obvious next candidate. **It was
tried and it loses.** The branch `read-caller-experiment` carries the whole thing, working and green; do not
redo it without reading this. `read` on DEFAULT, against 1.377 M ops/s for the loop:

| | ops/s | |
| --- | --- | --- |
| readers taking a context object instead of `(from, to, buffer)` | 1.249 M | −9 % |
| … driven by a generated `ToGlobCaller`, first cut | 1.084 M | −23 % |
| … keys densified, so a `tableswitch` and not a `lookupswitch` | 1.099 M | −22 % |
| … `readNext()` hoisted back into `nextKey()` | 1.294 M | −8 % |

Three things to keep from that, all of them non-obvious:

- **The context object alone costs 9 %**, where `WriteBuffer` cost 2 to 3 % on the write side. Three values
  passed in registers become three field loads per field, and reading is the more memory-bound of the two.
  It buys nothing unless the caller that needs it pays for itself.
- **`readNext()` is what reading costs**, not the dispatch. Moving it out of the loop and into the readers —
  4 call sites becoming one per reader — cost 18 % on its own. That is also why the caller cannot win here:
  on write the per-field work is a handful of byte moves, so the megamorphic dispatch is a large share of it;
  on read it is the scan, so removing the dispatch saves little.
- **A FIX tag is a sparse key.** The generator emits a `lookupswitch` — a binary search — past
  `span > 2n + 8`, where the other codecs on this SPI key on a dense field index and get a `tableswitch`.
  Densifying the keys is a one-array indirection and was worth ~1 %, so that is not what was wrong, but it is
  a trap worth knowing about.

What is left against the loop is the callback protocol itself : a `KeySource` call, two flags — one for a
reader that does not consume its tag, one for the group that leaves the next one ready — and a struct
indirection per tag, where the loop has direct control flow.

## Architecture

A FIX engine where messages are `Glob` instances (globs-framework dynamic records) rather than generated
classes. The binding between a `GlobType` and the FIX wire format is entirely annotation-driven and
resolved once at startup into flat lookup tables; the per-message hot path then does no reflection.

### 1. Dictionary (`fix/dictionary`)

`ReadFixDictionary.parse(version, Supplier<Reader>, FieldFactory)` reads a QuickFIX-style XML dictionary
(`src/test/resources/FIX44.xml`) into a `FixModel` (`FixField`, `FixComponent`, `FixGroup`,
`FixMessageDescriptor`, `FixHeader`, `FixTrailer`). It runs **four SAX passes over the same document** —
fields, then components, then messages, then header/trailer — because of forward references. That is why
it takes a `Supplier<Reader>` rather than a `Reader`.

`fix/dictionary/reverter/FixModelToGlobType` goes the other way: it generates `GlobType`s straight from a
`FixModel`. Its `switch` on the XML `type` attribute is the authority on FIX-type → glob-field mapping:

| FIX type                        | glob field         |
|---------------------------------|--------------------|
| `INT`, `NUMINGROUP`, `LENGTH`, `SEQNUM` | `IntegerField`     |
| `BOOLEAN`                       | `BooleanField`     |
| `FLOAT`                         | `DoubleField`      |
| `MULTIPLEVALUESTRING`           | `StringArrayField` |
| everything else                 | `StringField`      |

Adding a FIX type means touching three places in step: this switch, the writer switch in
`SerializerFixWriterBuilder.extracted`, and the reader switch in
`DeserializerFixReaderBuilder.getFieldReaderIntHashMap`. The latter two throw on unhandled field types.

### 2. Binding a GlobType to FIX (`fix/dictionary/model`)

Matching is by **FIX name**, never by Java field name — the Java names are free:

- `FixMessageType.create("NewOrderSingle")` on a `GlobType` → `<message name="NewOrderSingle">`.
- `FixFieldType.create("ClOrdID")` on a `Field` → `<field name="ClOrdID">`, which supplies the tag number.
- `FixGroupType.create("NoSecurityAltID")` on the **target type** of a `GlobArrayField` → repeating group;
  the annotation value is the group's *count* field name.
- `FixComponentType.create("Instrument")` marks a component type, but components are **flattened**: when
  building writers/readers the component's fields are looked up directly on the enclosing message's
  `GlobType`. So a message can declare `Symbol` inline instead of nesting an `Instrument` glob.

Tags present in the dictionary but absent from the `GlobType` are skipped on read
(`NoFieldDirectFieldReader`, and `NoFieldGroupReader` for whole groups) and logged at debug on write.
Both builders always merge `FixAdminModel.MODEL` (Logon, Logout, Heartbeat, TestRequest, ResendRequest,
SequenceReset, Reject) into the caller's `GlobModel` — never redeclare those.

`src/test/java/org/globsframework/fix/fix44/**` and `HeaderType`/`TrailerType` are the reference examples
of hand-written bound types.

### 3. Serializer / deserializer

`SerializerFixWriterBuilder.create(...)` and `DeserializerFixReaderBuilder.create(...)` walk the
`FixModel` × `GlobModel` once and build, per message type, a table of `FieldWrite` / `FieldReader`
closures (readers keyed by tag in an `IntHashMap`). Each closure captures a typed glob accessor — a
`GlobGetAccessor` on the to-Glob side, a `GlobSetAccessor` on the from-Glob side — so serialization is a straight
loop over primitives and neither direction looks a `Field` up on a `Glob`.

**The writers are held in dictionary order, and that is the wire order** — `newOrderedWrites()` in the
builder, a `LinkedHashMap` at every level (message, flattened component, group). FIX only *mandates* it
inside a repeating group, whose entries every engine frames on the delimiter — the group's first field in
the dictionary — but a `Field` hashes on its identity, so a `HashMap` here would also make the layout of
every message depend on what the JVM allocated before the `GlobType` was built.
`FixProtocolConformanceTest` pins the order, on a whole Glob and on a message built through
`FixMessage.update`. Every message is written whole : a field that is not set writes nothing, and a group
with no entry is omitted entirely, count included.

**`MessageFieldWrite` runs those writers one of two ways**, over one rendering per field:

- a `FromGlobCaller` from core's `org.globsframework.core.model.caller`, asked for through
  `generatedCallerFor("fix.write", type, functions, order)`. The calls are unrolled, one monomorphic call
  site per field, and the values are read out of the Glob by the emitted code — `FieldWrite.call`, the
  `FromGlobFunction` side, takes the value it is handed. `order` is the map's key order, i.e. the
  dictionary's: a caller walks the fields by index unless it is told otherwise, and `HeaderType` is exactly
  a type that declares them in another order (SendingTime before PossDupFlag, where FIX 4.4 has 43 before
  52). The fields FIX does not bind are not in the map, so no call site is emitted for them.
- the loop, when nothing in the JVM generates. `FieldWrite.writeAt` then reads the value itself through the
  typed `GlobGetAccessor` it holds.

Both entry points are on the same writer class and share one `private static write(...)` — that is why
there is no duplicated rendering, and why the accessor stays a field even when the caller is used. **Do not
hoist that read into the loop** (a `GlobGetAccessor[]` walked next to the dispatch): it turns one call site
per writer class into one for all of them and measured **−27 %** on write. And it is `generatedCallerFor`
rather than `callerFor` for the same reason — the `LoopFromGlobCaller` core would hand back reads through
`Glob.getValue(Field)` and is a worse fallback than the one here.

`GeneratedWriteCallerTest` is what holds the two together: the same message rendered both ways, asserted
byte for byte, plus the tag order under the caller.

`FixWriterImpl` owns a single 1 MB `byte[]`, and the single `WriteBuffer` that wraps it and carries the
write index the `FieldWrite`s advance — both reused for every message. It writes the body starting at
`OFFSET = 32` and then **back-fills** `8=BeginString`, `9=BodyLength` and `35=MsgType` in the bytes
*before* offset 32, asserting it lands exactly on 32; the header prefix must therefore fit in those 32
bytes. Checksum (tag 10) is summed over the finished message. `MsgSeqNum` and `SendingTime` are filled in
only if unset, and the sequence number is reverted if writing throws.

`FixReaderImpl` streams from a `ByteReader` with a 10 KB buffer, dispatching on tag id. `MsgType` routing
uses `oneLetter`/`twoLetters` arrays rather than a map lookup. A message that frames and checksums
correctly but whose body cannot be decoded comes back as a `FixMessageValue` carrying a
`DecodeError(sessionRejectReason, text)` — the session layer is expected to Reject and consume the seqnum
rather than drop the connection.

Some writers have specialized variants chosen from dictionary metadata, e.g. `StringFieldWrite.create`
returns `SmallStringFieldWrite` when the field's enum values are all one character
(`FixField.getMaxEnumLength() == 1`).

`MULTIPLEVALUESTRING` is a `StringArrayField` serialized as a single space-separated tag value
(`18=1 G`); empty elements are dropped on write, and the reader splits on `" "`.

### 4. Session layer (`fix/engine`)

`FixSessionImpl` (the largest class, ~1.3k lines) is a state machine over nested `SessionState`
implementations — `InitiatorSessionState`, `WaitForLogonGapSessionState`, `ConnectedSessionState`,
`ConnectedGapSessionState`, plus an `IgnoreAndReturnPrevious` wrapper. It implements the FIX 4.4 session
rules: logon/logout handshake, sequence-number checking, gap detection and gap fill, resend requests,
`PossDupFlag`/`OrigSendingTime` validation, heartbeats and test requests.

Wiring, outermost first:

- `FixServer` — accept loop over a `ServerSocket`; `acceptAsAcceptor()` or `acceptAsInitiator(...)`.
- `FixConnectionFactory` — wraps a `Socket` into a `ByteReader` + `Publish` and builds
  `NewAcceptorFixConnectionImpl` / `NewInitiatorFixConnectionImpl`.
- `FixEngineInit` — after the peer's CompIDs are known, pulls the reader/writer/`HeaderDesc` from
  `SerializerProvider`, the seqnum state and resend cache from `FixInfoProvider.DataAdapt`, and starts
  `FixSessionImpl` on the executor.

Application code plugs in via `UserLogonSessionFactory` → `UserSession` → `AppMessageReceiver`.
Replay/resend storage is behind `FixMessageRepository` (`InMemoryCacheDataAdapt` vs `NoCacheDataAdapt`),
and inbound sequence state behind `ClientSeqMsgId`.

`HeaderDesc` resolves the session-critical header fields (`SenderCompID`, `TargetCompID`, `MsgType`,
`MsgSeqNum`, `PossDupFlag`, `SendingTime`, `OrigSendingTime`) by FIX name and throws if any is missing —
a header `GlobType` must declare all seven.

`FormatDateTime` caches the date prefix and pre-rendered second/millisecond byte pairs to format UTC
timestamps without allocating; use `autoRefreshUTC(scheduler)` in production and `shouldRefreshUTC()`
(lazy, no scheduler) in tests.

## Conventions

- Java 22 pattern-matching `switch` over `Field` / `FixElement` subtypes is the idiom for both builders;
  follow it rather than adding `instanceof` chains.
- The `Utils` helpers write into a caller-supplied `byte[]` at an index and return the new index
  (`Utils.transfert`, `Utils.transfertInt`); a `FieldWrite`, on the outer boundary, takes a `WriteBuffer`
  instead and stores the index back into it — read `out.at` into a local, run the helpers over it, store it
  once at the end, and leave it untouched when the field writes nothing. Avoid allocation and avoid growing
  these methods — commit `ab92e9b` deliberately reduced hot-method size for JIT inlining.
- ISO-8859-1 everywhere on the wire; SOH is `0x1`.
