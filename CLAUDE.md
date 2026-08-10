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
closures (readers keyed by tag in an `IntHashMap`). Each closure captures a typed glob accessor, so
serialization is a straight loop over primitives.

`FixWriterImpl` owns a single 1 MB `byte[]`. It writes the body starting at `OFFSET = 32` and then
**back-fills** `8=BeginString`, `9=BodyLength` and `35=MsgType` in the bytes *before* offset 32, asserting
it lands exactly on 32; the header prefix must therefore fit in those 32 bytes. Checksum (tag 10) is
summed over the finished message. `MsgSeqNum` and `SendingTime` are filled in only if unset, and the
sequence number is reverted if writing throws.

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
- Hot-path code writes into caller-supplied `byte[]` at an index and returns the new index
  (`Utils.transfert`, `Utils.transfertInt`). Avoid allocation and avoid growing these methods — commit
  `ab92e9b` deliberately reduced hot-method size for JIT inlining.
- ISO-8859-1 everywhere on the wire; SOH is `0x1`.
