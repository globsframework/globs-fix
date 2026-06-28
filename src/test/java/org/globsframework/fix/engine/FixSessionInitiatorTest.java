package org.globsframework.fix.engine;

import org.globsframework.core.metamodel.GlobModel;
import org.globsframework.core.metamodel.impl.DefaultGlobModel;
import org.globsframework.core.model.MutableGlob;
import org.globsframework.fix.HeaderType;
import org.globsframework.fix.TrailerType;
import org.globsframework.fix.FormatDateTime;
import org.globsframework.fix.deserializer.DeserializerFixReaderBuilder;
import org.globsframework.fix.deserializer.FixMessageValue;
import org.globsframework.fix.deserializer.FixReader;
import org.globsframework.fix.deserializer.FixReaderBuilder;
import org.globsframework.fix.dictionary.FixModel;
import org.globsframework.fix.dictionary.admin.*;
import org.globsframework.fix.dictionary.xml.FieldFactoryImpl;
import org.globsframework.fix.dictionary.xml.ReadFixDictionary;
import org.globsframework.fix.fix44.app.QuoteRequestType;
import org.globsframework.fix.serializer.Publish;
import org.globsframework.fix.serializer.SerializerFixWriterBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FixSessionInitiatorTest {
    private ScheduledExecutorService executorService = Executors.newSingleThreadScheduledExecutor();
    private FixModel fixModel;
    private GlobModel globModel;
    private SerializerFixWriterBuilder fixWriterBuilder;
    private FixReaderBuilder fixReaderBuilder;
    private FixReader fixReader;
    private FixSessionImpl fixSession;
    private TestUserSession userSession;
    private CompletableByteReader completableByteReader;

    @BeforeEach
    void setUp() throws IOException {
        fixModel = ReadFixDictionary.parse("fix44", () ->
                new InputStreamReader(getClass().getClassLoader().getResourceAsStream("FIX44.xml"),
                        StandardCharsets.UTF_8), new FieldFactoryImpl());

        globModel = new DefaultGlobModel(QuoteRequestType.TYPE);
        fixWriterBuilder = SerializerFixWriterBuilder.create(fixModel, globModel, HeaderType.TYPE, TrailerType.TYPE,
                FormatDateTime.autoRefreshUTC(executorService));

        fixReaderBuilder = DeserializerFixReaderBuilder.create(fixModel, globModel, HeaderType.TYPE, TrailerType.TYPE);
        completableByteReader = new CompletableByteReader();
        fixReader = fixReaderBuilder.createReader(completableByteReader);

        userSession = new TestUserSession();
        final FixInfoProvider.DataAdapt fixMessageRepository = InMemoryCacheDataAdapt.create(20, HeaderType.msgSeqNum);
        fixSession = new FixSessionImpl(executorService, fixMessageRepository.createWriter(new Publish() {
            @Override
            public void publish(byte[] data, int offset, int length) {
                completableByteReader.getNext()
                        .complete(Arrays.copyOfRange(data, offset, offset + length));
            }
        }, fixWriterBuilder),
                userSession, fixMessageRepository.clientSeqMsgId(),
                fixMessageRepository.getSelfMsgSeqProvider(), fixMessageRepository.getCachedData(),
                HeaderType.getHeaderDesc(), () -> {
        },
                true, new FixSessionImpl.Option(-1));
    }

    @Test
    void nominalLogon() throws Exception {
        final FixMessageValue read = fixReader.read();
        assertEquals(LogonType.TYPE, read.message().getType());
        fixSession.newMessage(new FixMessageValue(getNextHeader(1), LogonType.create(10000), null));
        userSession.connected.get(1, TimeUnit.SECONDS);
        fixSession.newMessage(new FixMessageValue(getNextHeader(2), QuoteRequestType.TYPE.instantiate(), null));
        final FixMessageValue message = userSession.getMessage();
        assertEquals(QuoteRequestType.TYPE, message.message().getType());
        fixSession.newMessage(new FixMessageValue(getNextHeader(3), LogoutType.create("Bye"), null));
        final FixMessageValue bye = fixReader.read();
        assertEquals(LogoutType.TYPE, bye.message().getType());
        assertEquals("Requested", bye.message().get(LogoutType.text));
    }

    @Test
    void shutdown() throws Exception {
        final FixMessageValue read = fixReader.read();
        assertEquals(LogonType.TYPE, read.message().getType());
        fixSession.newMessage(new FixMessageValue(getNextHeader(1), LogonType.create(10000), null));
        userSession.connected.get(1, TimeUnit.SECONDS);
        fixSession.logout();
    }

    @Test
    void gapAtStart() throws Exception {
        final FixMessageValue read = fixReader.read();
        assertEquals(LogonType.TYPE, read.message().getType());
        fixSession.newMessage(new FixMessageValue(getNextHeader(10), LogonType.create(10000), null));
        userSession.connected.get(1, TimeUnit.SECONDS);
        final FixMessageValue message = fixReader.read();
        assertEquals(ResendRequestType.TYPE, message.message().getType());
        assertEquals(1, message.message().get(ResendRequestType.beginSeqNo));
        assertEquals(9, message.message().get(ResendRequestType.endSeqNo));
        fixSession.newMessage(new FixMessageValue(getNextHeader(11), QuoteRequestType.create("11"), null));
        assertTrue(userSession.checkEmpty());
        fixSession.newMessage(new FixMessageValue(getNextHeader(1), QuoteRequestType.create("1"), null));
        fixSession.newMessage(new FixMessageValue(getNextHeader(2), QuoteRequestType.create("2"), null));
        fixSession.newMessage(new FixMessageValue(getNextHeader(3), SequenceResetType.create(true, 10), null));
        fixSession.newMessage(new FixMessageValue(getNextHeader(12), QuoteRequestType.create("12"), null));
        assertEquals("1", userSession.getMessage().message().get(QuoteRequestType.quoteReqID));
        assertEquals("2", userSession.getMessage().message().get(QuoteRequestType.quoteReqID));
        assertEquals("11", userSession.getMessage().message().get(QuoteRequestType.quoteReqID));
        assertEquals("12", userSession.getMessage().message().get(QuoteRequestType.quoteReqID));
    }

    @Test
    void testRequestReSend() throws Exception {
        final FixMessageValue read = fixReader.read();
        assertEquals(LogonType.TYPE, read.message().getType());
        fixSession.newMessage(new FixMessageValue(getNextHeader(1), LogonType.create(10000), null));
        userSession.connected.get(1, TimeUnit.SECONDS);

        int[] seNum = new int[5];
        for (int i = 0; i < 5; i++) {
            String content = Integer.toString(i);
            final MutableGlob publish = userSession.publish(QuoteRequestType.create(content));
            seNum[i] = publish.get(HeaderType.msgSeqNum);
            final FixMessageValue msg2 = fixReader.read();
            assertEquals(QuoteRequestType.TYPE, msg2.message().getType());
            assertEquals(content, msg2.message().get(QuoteRequestType.quoteReqID));
        }

        fixSession.newMessage(new FixMessageValue(getNextHeader(2),
                ResendRequestType.create(seNum[2], seNum[3]), null));

        assertEquals("2", fixReader.read().message().get(QuoteRequestType.quoteReqID));
        assertEquals("3", fixReader.read().message().get(QuoteRequestType.quoteReqID));

        userSession.publish(QuoteRequestType.create("6"));
        assertEquals("6", fixReader.read().message().get(QuoteRequestType.quoteReqID));
    }

    @Test
    void logoutDuringGapShutsDownSession() throws Exception {
        // Connect normally
        assertEquals(LogonType.TYPE, fixReader.read().message().getType());
        fixSession.newMessage(new FixMessageValue(getNextHeader(1), LogonType.create(10000), null));
        userSession.connected.get(1, TimeUnit.SECONDS);

        // Peer sends app message at seq 5 (gap 2-4) → ConnectedGapSessionState
        fixSession.newMessage(new FixMessageValue(getNextHeader(5), QuoteRequestType.create("5"), null));
        FixMessageValue resendRequest = fixReader.read();
        assertEquals(ResendRequestType.TYPE, resendRequest.message().getType());

        // Peer sends Logout while gap is still open
        // Before fix: shutdown() not called → schedulers keep running, userSession not released
        // After fix: shutdown() called → clean teardown
        fixSession.newMessage(new FixMessageValue(getNextHeader(6), LogoutType.create("Bye"), null));

        FixMessageValue logoutResponse = fixReader.read();
        assertEquals(LogoutType.TYPE, logoutResponse.message().getType());
        assertEquals("Requested from gap", logoutResponse.message().get(LogoutType.text));
    }

    @Test
    void resendRequestWithMixedAdminAndAppMessages() throws Exception {
        // Session sends Logon (seq 1, admin) — this is the message that will trigger the GapFill
        final FixMessageValue logon = fixReader.read();
        assertEquals(LogonType.TYPE, logon.message().getType());

        // Peer responds with Logon
        fixSession.newMessage(new FixMessageValue(getNextHeader(1), LogonType.create(10000), null));
        userSession.connected.get(1, TimeUnit.SECONDS);

        // User publishes 3 app messages — stored in cache as seqs 2, 3, 4
        userSession.publish(QuoteRequestType.create("0"));
        fixReader.read(); // drain seq 2
        userSession.publish(QuoteRequestType.create("1"));
        fixReader.read(); // drain seq 3
        userSession.publish(QuoteRequestType.create("2"));
        fixReader.read(); // drain seq 4

        // Peer sends ResendRequest covering seq 1 (Logon=admin) and seqs 2,3 (app)
        // Before the fix: NPE via auto-unboxing of null seqNum from shared header
        fixSession.newMessage(new FixMessageValue(getNextHeader(2), ResendRequestType.create(1, 3), null));

        // GapFill covers seq 1 (skipped Logon), NewSeqNo=2 (original seqNum of first replayed app message)
        final FixMessageValue gapFill = fixReader.read();
        assertEquals(SequenceResetType.TYPE, gapFill.message().getType());
        assertTrue(gapFill.message().get(SequenceResetType.gapFillFlag));
        assertEquals(1, gapFill.header().get(HeaderType.msgSeqNum));
        assertEquals(2, gapFill.message().get(SequenceResetType.newSeqNo));

        // Replay of QuoteRequest "0" with its original seqNum
        final FixMessageValue replay2 = fixReader.read();
        assertEquals(QuoteRequestType.TYPE, replay2.message().getType());
        assertEquals("0", replay2.message().get(QuoteRequestType.quoteReqID));
        assertEquals(2, replay2.header().get(HeaderType.msgSeqNum));
        assertTrue(replay2.header().get(HeaderType.possDupFlag));

        // Replay of QuoteRequest "1" with its original seqNum
        final FixMessageValue replay3 = fixReader.read();
        assertEquals(QuoteRequestType.TYPE, replay3.message().getType());
        assertEquals("1", replay3.message().get(QuoteRequestType.quoteReqID));
        assertEquals(3, replay3.header().get(HeaderType.msgSeqNum));
        assertTrue(replay3.header().get(HeaderType.possDupFlag));
    }

    @Test
    void logonWithPastAndFutureBufferedMessages() throws Exception {
        // Session auto-sends Logon (seq 1)
        assertEquals(LogonType.TYPE, fixReader.read().message().getType());

        // Peer sends an app message at seq 5 before their Logon — gap 1-4 detected
        // → WaitForLogonGapSessionState created, session sends ResendRequest(1, 4)
        // This is the scenario that triggers the NPE bug: logon() will later be called
        // with non-empty pastAppMessage but appMessageReceiver still null (before fix)
        fixSession.newMessage(new FixMessageValue(getNextHeader(5), QuoteRequestType.create("5"), null));
        FixMessageValue resendRequest = fixReader.read();
        assertEquals(ResendRequestType.TYPE, resendRequest.message().getType());
        assertEquals(1, resendRequest.message().get(ResendRequestType.beginSeqNo));
        assertEquals(4, resendRequest.message().get(ResendRequestType.endSeqNo));

        // Peer fills the past gap (seqs 1-4) — buffers into pastAppMessage
        fixSession.newMessage(new FixMessageValue(getNextHeader(1), QuoteRequestType.create("1"), null));
        fixSession.newMessage(new FixMessageValue(getNextHeader(2), QuoteRequestType.create("2"), null));
        fixSession.newMessage(new FixMessageValue(getNextHeader(3), QuoteRequestType.create("3"), null));
        fixSession.newMessage(new FixMessageValue(getNextHeader(4), QuoteRequestType.create("4"), null));
        // nextExpectedPastSeqNum == firstReceivedSeqNum(5): gap fully resolved

        // Peer sends a future app message (seq 6) — buffers into futureAppMessage
        fixSession.newMessage(new FixMessageValue(getNextHeader(6), QuoteRequestType.create("6"), null));

        // Peer sends Logon at seq 7 — triggers WaitForLogonGapSessionState.logon(), branch 1
        // Before fix: NPE — appMessageReceiver null when dispatching pastAppMessage
        // After fix: connect() called first, then buffered messages are dispatched
        fixSession.newMessage(new FixMessageValue(getNextHeader(7), LogonType.create(10000), null));
        userSession.connected.get(1, TimeUnit.SECONDS);

        assertEquals("1", userSession.getMessage().message().get(QuoteRequestType.quoteReqID));
        assertEquals("2", userSession.getMessage().message().get(QuoteRequestType.quoteReqID));
        assertEquals("3", userSession.getMessage().message().get(QuoteRequestType.quoteReqID));
        assertEquals("4", userSession.getMessage().message().get(QuoteRequestType.quoteReqID));
        assertEquals("6", userSession.getMessage().message().get(QuoteRequestType.quoteReqID));
        assertTrue(userSession.checkEmpty());
    }

    private MutableGlob getNextHeader(int seqNum) {
        return HeaderType.create("AF", "BNP")
                .set(HeaderType.msgSeqNum, seqNum);
    }

}