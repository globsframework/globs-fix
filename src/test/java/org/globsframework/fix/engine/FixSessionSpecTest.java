package org.globsframework.fix.engine;

import org.globsframework.core.metamodel.GlobModel;
import org.globsframework.core.metamodel.impl.DefaultGlobModel;
import org.globsframework.core.model.Glob;
import org.globsframework.core.model.MutableGlob;
import org.globsframework.fix.FormatDateTime;
import org.globsframework.fix.HeaderType;
import org.globsframework.fix.TrailerType;
import org.globsframework.fix.deserializer.DeserializerFixReaderBuilder;
import org.globsframework.fix.deserializer.FixMessageValue;
import org.globsframework.fix.deserializer.FixReader;
import org.globsframework.fix.dictionary.FixModel;
import org.globsframework.fix.dictionary.admin.*;
import org.globsframework.fix.dictionary.xml.FieldFactoryImpl;
import org.globsframework.fix.dictionary.xml.ReadFixDictionary;
import org.globsframework.fix.fix44.app.QuoteRequestType;
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
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/*
Session-level test battery following the FIX 4.4 specification (volume 2) test cases.
Cases already covered elsewhere are not repeated here : initiator logon (FixSessionInitiatorTest),
resend with mixed admin/app messages, MsgSeqNum too low with/without PossDupFlag, complex gap
scenarios (FixSessionGapTest), logout handshake.
 */
class FixSessionSpecTest {
    private final ScheduledExecutorService executorService = Executors.newSingleThreadScheduledExecutor();
    private final AtomicBoolean shutdownCalled = new AtomicBoolean();
    private FixModel fixModel;
    private GlobModel globModel;
    private SerializerFixWriterBuilder fixWriterBuilder;
    private FixReader fixReader;
    private FixSessionImpl fixSession;
    private TestUserSession userSession;
    private CompletableByteReader completableByteReader;

    static class ConfigurableUserSession extends TestUserSession {
        private final int heartBtInt;

        ConfigurableUserSession(int heartBtInt) {
            this.heartBtInt = heartBtInt;
        }

        @Override
        public Glob getLogon() {
            return LogonType.create(heartBtInt);
        }
    }

    @BeforeEach
    void setUp() throws IOException {
        fixModel = ReadFixDictionary.parse("fix44", () ->
                new InputStreamReader(getClass().getClassLoader().getResourceAsStream("FIX44.xml"),
                        StandardCharsets.UTF_8), new FieldFactoryImpl());
        globModel = new DefaultGlobModel(HeartbeatType.TYPE, LogonType.TYPE, QuoteRequestType.TYPE);
        fixWriterBuilder = SerializerFixWriterBuilder.create(fixModel, globModel, HeaderType.TYPE, TrailerType.TYPE,
                FormatDateTime.autoRefreshUTC(executorService));
        completableByteReader = new CompletableByteReader();
        fixReader = DeserializerFixReaderBuilder.create(fixModel, globModel, HeaderType.TYPE, TrailerType.TYPE)
                .createReader(completableByteReader);
    }

    private void createSession(boolean isInitiator, TestUserSession user) {
        userSession = user;
        final FixInfoProvider.DataAdapt dataAdapt = InMemoryCacheDataAdapt.create(20, HeaderType.msgSeqNum);
        fixSession = new FixSessionImpl(executorService,
                dataAdapt.createWriter((data, offset, length) ->
                                completableByteReader.getNext().complete(Arrays.copyOfRange(data, offset, offset + length)),
                        fixWriterBuilder),
                user, dataAdapt.clientSeqMsgId(), dataAdapt.getSelfMsgSeqProvider(), dataAdapt.getCachedData(),
                HeaderType.getHeaderDesc(), () -> shutdownCalled.set(true), isInitiator, new FixSessionImpl.Option(-1));
    }

    private MutableGlob getHeader(int seqNum) {
        return HeaderType.create("AF", "BNP")
                .set(HeaderType.msgSeqNum, seqNum);
    }

    private void connectInitiator() throws Exception {
        createSession(true, new TestUserSession());
        assertEquals(LogonType.TYPE, fixReader.read().message().getType());
        fixSession.newMessage(new FixMessageValue(getHeader(1), LogonType.create(10000), null));
        userSession.connected.get(1, TimeUnit.SECONDS);
    }

    // spec 1a (acceptor) : valid Logon as first message => respond with Logon
    @Test
    void acceptorRespondsToValidLogon() throws Exception {
        createSession(false, new TestUserSession());
        fixSession.newMessage(new FixMessageValue(getHeader(1), LogonType.create(10000), null));
        final FixMessageValue logonResponse = fixReader.read();
        assertEquals(LogonType.TYPE, logonResponse.message().getType());
        userSession.connected.get(1, TimeUnit.SECONDS);

        fixSession.newMessage(new FixMessageValue(getHeader(2), QuoteRequestType.create("2"), null));
        assertEquals("2", userSession.getMessage().message().get(QuoteRequestType.quoteReqID));
    }

    // spec 1b (acceptor) : first message is not a Logon => disconnect
    @Test
    void acceptorDisconnectsWhenFirstMessageIsNotALogon() throws Exception {
        createSession(false, new TestUserSession());
        fixSession.newMessage(new FixMessageValue(getHeader(1), QuoteRequestType.create("1"), null));
        assertTrue(shutdownCalled.get());
        assertFalse(userSession.connected.isDone());
        assertTrue(userSession.checkEmpty());
    }

    // spec 1b variant (acceptor) : admin message before Logon => disconnect
    @Test
    void acceptorDisconnectsOnHeartbeatBeforeLogon() throws Exception {
        createSession(false, new TestUserSession());
        fixSession.newMessage(new FixMessageValue(getHeader(1), HeartbeatType.create(), null));
        assertTrue(shutdownCalled.get());
        assertFalse(userSession.connected.isDone());
    }

    // spec 1S (acceptor) : Logon with MsgSeqNum too high => respond with Logon then ResendRequest
    @Test
    void acceptorRespondsLogonThenResendRequestOnLogonGap() throws Exception {
        createSession(false, new TestUserSession());
        fixSession.newMessage(new FixMessageValue(getHeader(5), LogonType.create(10000), null));
        userSession.connected.get(1, TimeUnit.SECONDS);

        final FixMessageValue logonResponse = fixReader.read();
        assertEquals(LogonType.TYPE, logonResponse.message().getType());
        final FixMessageValue resendRequest = fixReader.read();
        assertEquals(ResendRequestType.TYPE, resendRequest.message().getType());
        assertEquals(1, resendRequest.message().get(ResendRequestType.beginSeqNo));
        assertEquals(4, resendRequest.message().get(ResendRequestType.endSeqNo));
    }

    // spec 4b : TestRequest => respond with a Heartbeat carrying the same TestReqID
    @Test
    void testRequestIsAnsweredWithMatchingTestReqId() throws Exception {
        connectInitiator();
        fixSession.newMessage(new FixMessageValue(getHeader(2), TestRequestType.create("ping-1"), null));
        final FixMessageValue heartbeat = fixReader.read();
        assertEquals(HeartbeatType.TYPE, heartbeat.message().getType());
        assertEquals("ping-1", heartbeat.message().get(HeartbeatType.testReqID));

        // the TestRequest consumed a seqNum : next message processed without gap detection
        fixSession.newMessage(new FixMessageValue(getHeader(3), QuoteRequestType.create("3"), null));
        assertEquals("3", userSession.getMessage().message().get(QuoteRequestType.quoteReqID));
    }

    // spec 10 : a session-level Reject consumes a seqNum and the session goes on
    @Test
    void rejectReceivedConsumesSeqNumAndSessionContinues() throws Exception {
        connectInitiator();
        fixSession.newMessage(new FixMessageValue(getHeader(2), RejectType.create(1, "D", "not for me"), null));
        fixSession.newMessage(new FixMessageValue(getHeader(3), QuoteRequestType.create("3"), null));
        assertEquals("3", userSession.getMessage().message().get(QuoteRequestType.quoteReqID));

        // no ResendRequest was interleaved : the next published message is the app one
        userSession.publish(QuoteRequestType.create("out"));
        final FixMessageValue published = fixReader.read();
        assertEquals(QuoteRequestType.TYPE, published.message().getType());
    }

    // spec 6 : SequenceReset-GapFill in sequence jumps the expected seqNum forward
    @Test
    void sequenceResetGapFillJumpsForward() throws Exception {
        connectInitiator();
        fixSession.newMessage(new FixMessageValue(getHeader(2), SequenceResetType.create(true, 6), null));
        fixSession.newMessage(new FixMessageValue(getHeader(6), QuoteRequestType.create("6"), null));
        assertEquals("6", userSession.getMessage().message().get(QuoteRequestType.quoteReqID));

        userSession.publish(QuoteRequestType.create("out"));
        assertEquals(QuoteRequestType.TYPE, fixReader.read().message().getType());
    }

    // spec 7 : SequenceReset-Reset (GapFillFlag=N) applies NewSeqNo and its own MsgSeqNum is ignored
    @Test
    void sequenceResetResetModeIgnoresItsOwnMsgSeqNum() throws Exception {
        connectInitiator();
        // MsgSeqNum 50 would be a gap : it must be ignored in reset mode
        fixSession.newMessage(new FixMessageValue(getHeader(50), SequenceResetType.create(false, 8), null));
        fixSession.newMessage(new FixMessageValue(getHeader(8), QuoteRequestType.create("8"), null));
        assertEquals("8", userSession.getMessage().message().get(QuoteRequestType.quoteReqID));

        userSession.publish(QuoteRequestType.create("out"));
        assertEquals(QuoteRequestType.TYPE, fixReader.read().message().getType());
    }

    // spec 5 : ResendRequest with EndSeqNo=0 means "up to the latest" : replay all with PossDupFlag
    @Test
    void resendRequestToInfinityReplaysAllAppMessages() throws Exception {
        connectInitiator();
        userSession.publish(QuoteRequestType.create("a"));
        assertEquals("a", fixReader.read().message().get(QuoteRequestType.quoteReqID)); // seq 2
        userSession.publish(QuoteRequestType.create("b"));
        assertEquals("b", fixReader.read().message().get(QuoteRequestType.quoteReqID)); // seq 3

        fixSession.newMessage(new FixMessageValue(getHeader(2), ResendRequestType.create(2, 0), null));

        final FixMessageValue replayA = fixReader.read();
        assertEquals("a", replayA.message().get(QuoteRequestType.quoteReqID));
        assertEquals(2, replayA.header().get(HeaderType.msgSeqNum));
        assertTrue(replayA.header().get(HeaderType.possDupFlag));
        assertNotNull(replayA.header().get(HeaderType.origSendingTime));

        final FixMessageValue replayB = fixReader.read();
        assertEquals("b", replayB.message().get(QuoteRequestType.quoteReqID));
        assertEquals(3, replayB.header().get(HeaderType.msgSeqNum));
        assertTrue(replayB.header().get(HeaderType.possDupFlag));
    }

    // spec 3 (out) : send a Heartbeat when no message has been sent for HeartBtInt
    @Test
    void heartbeatIsSentAfterSendInactivity() throws Exception {
        createSession(true, new ConfigurableUserSession(1));
        assertEquals(LogonType.TYPE, fixReader.read().message().getType());
        fixSession.newMessage(new FixMessageValue(getHeader(1), LogonType.create(10000), null));
        userSession.connected.get(1, TimeUnit.SECONDS);

        // nothing is sent : a Heartbeat (without TestReqID) must show up after ~1s
        final FixMessageValue heartbeat = fixReader.read();
        assertEquals(HeartbeatType.TYPE, heartbeat.message().getType());
        assertNull(heartbeat.message().get(HeartbeatType.testReqID));
    }

    // spec 3 (in) : send a TestRequest when the peer is silent, disconnect without answer
    @Test
    void testRequestSentOnSilentPeerThenDisconnect() throws Exception {
        createSession(true, new TestUserSession());
        assertEquals(LogonType.TYPE, fixReader.read().message().getType());
        // the peer announces a 1 second heartbeat then goes silent
        fixSession.newMessage(new FixMessageValue(getHeader(1), LogonType.create(1), null));
        userSession.connected.get(1, TimeUnit.SECONDS);

        final FixMessageValue testRequest = fixReader.read();
        assertEquals(TestRequestType.TYPE, testRequest.message().getType());
        assertNotNull(testRequest.message().get(TestRequestType.testReqID));

        final long end = System.currentTimeMillis() + 3000;
        while (!shutdownCalled.get() && System.currentTimeMillis() < end) {
            Thread.sleep(20);
        }
        assertTrue(shutdownCalled.get(), "connection must be shut down when the TestRequest stays unanswered");
    }

    // spec 3/4a : a Heartbeat answering the TestRequest keeps the session alive
    @Test
    void heartbeatAnswerToTestRequestKeepsSessionAlive() throws Exception {
        createSession(true, new TestUserSession());
        assertEquals(LogonType.TYPE, fixReader.read().message().getType());
        fixSession.newMessage(new FixMessageValue(getHeader(1), LogonType.create(1), null));
        userSession.connected.get(1, TimeUnit.SECONDS);

        final FixMessageValue testRequest = fixReader.read();
        assertEquals(TestRequestType.TYPE, testRequest.message().getType());
        fixSession.newMessage(new FixMessageValue(getHeader(2),
                HeartbeatType.create(testRequest.message().get(TestRequestType.testReqID)), null));

        // the session stays alive and keeps probing : a second TestRequest shows up
        final FixMessageValue nextTestRequest = fixReader.read();
        assertEquals(TestRequestType.TYPE, nextTestRequest.message().getType());
        assertFalse(shutdownCalled.get());
    }
}
