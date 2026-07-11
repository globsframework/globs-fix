package org.globsframework.fix.engine;

import org.globsframework.core.metamodel.GlobType;
import org.globsframework.core.model.Glob;
import org.globsframework.core.model.MutableGlob;
import org.globsframework.fix.deserializer.FixMessageValue;
import org.globsframework.fix.dictionary.admin.*;
import org.globsframework.fix.serializer.FixWriter;
import org.globsframework.fix.serializer.MsgSeqProvider;
import org.globsframework.json.GSonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/*
Threading model :
 - the reader thread calls newMessage (a single thread per session).
 - the scheduler threads run the heartbeat tasks and the forced logout task.
 - application threads call appWriter.write, logout and registerOnClosed.
All the session state (state machine, sequence numbers, heartbeat scheduling, lifecycle)
is guarded by sessionLock. The hot-path timestamps written outside the lock stay volatile.
onClose runnables may be run while sessionLock is held : they must not block.
 */
public class FixSessionImpl implements FixMessageListener {
    private static final Logger log = LoggerFactory.getLogger(FixSessionImpl.class);
    public static final int DELAY_BETWEEN_CONNECT_AND_LOGON = 10;
    private final Object sessionLock = new Object();
    private final String ident;
    private final String identSend;
    private final String identReceive;
    private final ScheduledExecutorService scheduledExecutorService;
    private final FixWriter writer;
    private final MsgSeqProvider selfMsgSeqProvider;
    private final FixWriter appWriter;
    private final FixMessageRepository fixMessageRepository;
    private final HeaderDesc headerDesc;
    private final Shutdown shutdown;
    private final MutableGlob header;
    private final ClientSeqMsgId clientSeqMsgId;
    private final Option option;
    private final List<Runnable> onClose = new ArrayList<>(); // guarded by sessionLock
    private final CompletableFuture<Boolean> closedCompletable = new CompletableFuture<>();
    // written by the writer wrapper from any thread (app publish path is not under sessionLock)
    private volatile long lastWriteOut = -1;
    // read lock-free in AppFixWriter
    private volatile boolean closed;
    // the fields below are guarded by sessionLock (constructor writes happen before publication)
    private long heartbeatInMSIn;
    private long heartbeatInMSOut;
    private long lastMessageReceivedTimeStampInMS = -1;
    private String expectedHeartbeat;
    private ScheduledFuture<?> scheduleOut;
    private ScheduledFuture<?> scheduleIn;
    private UserSession userSession;
    private AppMessageReceiver appMessageReceiver;
    private int expectedNext;
    private SessionState sessionState;

    public void registerOnClosed(Runnable runnable) {
        synchronized (sessionLock) {
            if (!closed) {
                onClose.add(runnable);
                return;
            }
        }
        runnable.run();
    }

    public record Option(int delayBeforeForceLogout) {
        public static Option def() {
            return new Option(1);
        }
    }

    public FixSessionImpl(ScheduledExecutorService scheduledExecutorService, FixWriter fixWriter,
                          UserSession userSession,
                          ClientSeqMsgId clientSeqMsgId,
                          MsgSeqProvider selfMsgSeqProvider, FixMessageRepository fixMessageRepository, // intercept the fixWriter call to update, if needed, the data for replay.
                          HeaderDesc headerDesc,
                          Shutdown shutdown,
                          boolean isInitiator, Option option) {
        this.scheduledExecutorService = scheduledExecutorService;
        this.writer = new FixWriter() {
            @Override
            public void write(FixMessage fixMessage) {
                lastWriteOut = System.currentTimeMillis();
                fixWriter.write(fixMessage);
                if (log.isDebugEnabled()) {
                    log.debug("{} send : {}", identSend, fixMessage);
                }
            }
        };
        this.selfMsgSeqProvider = selfMsgSeqProvider;
        appWriter = new AppFixWriter(headerDesc);
        this.userSession = userSession;
        this.fixMessageRepository = fixMessageRepository;
        this.headerDesc = headerDesc;
        this.shutdown = shutdown;
        this.clientSeqMsgId = clientSeqMsgId;
        expectedNext = clientSeqMsgId.current() + 1;
        this.option = option;
        header = userSession.getHeader().duplicate();
        header.unset(headerDesc.sendingTime());
        header.unset(headerDesc.origSendingTime());
        header.unset(headerDesc.isDup());
        header.unset(headerDesc.seqNumField());
        final String senderCompId = header.get(headerDesc.senderCompIDField());
        final String targetCompId = header.get(headerDesc.targetCompIDField());
        ident = senderCompId + "-" + targetCompId;
        identSend = senderCompId + "->" + targetCompId;
        identReceive = senderCompId + "<-" + targetCompId;
        log.info(ident + " new session at " + selfMsgSeqProvider.current() + " and expected seqNum " + expectedNext + " from " + targetCompId);
        closedCompletable.whenComplete((aBoolean, throwable) -> {
            List<Runnable> toRun;
            synchronized (sessionLock) {
                toRun = new ArrayList<>(onClose);
                onClose.clear();
            }
            for (Runnable runnable : toRun) {
                try {
                    runnable.run();
                } catch (Exception e) {
                    log.warn("Error thrown on close " + e.getMessage(), e);
                }
            }
        });
        if (isInitiator) {
            sessionState = new InitiatorSessionState();
        } else {
            sessionState = new AcceptorSessionState();
        }
    }

    private static String jsonOut(Glob data, boolean withKind) {
        return data == null ? "null" : GSonUtils.encode(data, withKind);
    }

    @Override
    public void newMessage(FixMessageValue fixMessageValue) {
        if (log.isDebugEnabled()) {
            log.debug(identReceive + " receive : [" + jsonOut(fixMessageValue.header(), false) + "," + jsonOut(fixMessageValue.message(), true) +
                      ", " + jsonOut(fixMessageValue.trailer(), false) + "]");
        }

        synchronized (sessionLock) {
            lastMessageReceivedTimeStampInMS = System.currentTimeMillis();
            final int seqNum = fixMessageValue.header().get(headerDesc.seqNumField());
            sessionState = sessionState.checkSeqNum(seqNum, fixMessageValue);
            final Glob message = fixMessageValue.message();
            if (message != null) {
                final GlobType type = message.getType();
                if (FixAdminModel.TYPES.contains(type)) {
                    if (type == LogonType.TYPE) {
                        sessionState = sessionState.logon(seqNum, fixMessageValue);
                    } else if (type == LogoutType.TYPE) {
                        sessionState = sessionState.logout(seqNum, fixMessageValue);
                    } else if (type == HeartbeatType.TYPE) {
                        sessionState = sessionState.heartBeat(seqNum, fixMessageValue);
                    } else if (type == ResendRequestType.TYPE) {
                        sessionState = sessionState.resendRequest(seqNum, fixMessageValue);
                    } else if (type == SequenceResetType.TYPE) {
                        sessionState = sessionState.sequenceReset(seqNum, fixMessageValue);
                    } else if (type == RejectType.TYPE) {
                        sessionState = sessionState.rejectedMessage(seqNum, fixMessageValue);
                    } else if (type == TestRequestType.TYPE) {
                        sessionState = sessionState.testRequest(seqNum, fixMessageValue);
                    } else {
                        log.error("Bug : type " + type.getName() + " not managed.");
                    }
                } else {
                    sessionState = sessionState.appMessage(seqNum, fixMessageValue);
                }
            }
        }
    }

    interface SessionState {

        SessionState logon(int seqNum, FixMessageValue fixMessageValue);

        SessionState logout(int seqNum, FixMessageValue fixMessageValue);

        SessionState sequenceReset(int seqNum, FixMessageValue fixMessageValue);

        SessionState rejectedMessage(int seqNum, FixMessageValue fixMessageValue);

        SessionState appMessage(int seqNum, FixMessageValue fixMessageValue);

        SessionState checkSeqNum(int seqNum, FixMessageValue fixMessageValue);

        SessionState heartBeat(int seqNum, FixMessageValue fixMessageValue);

        SessionState resendRequest(int seqNum, FixMessageValue fixMessageValue);

        SessionState testRequest(int seqNum, FixMessageValue fixMessageValue);
    }

    private void connect(FixMessageValue fixMessageValue) {
        log.info(ident + " : user session connected.");
        final AppMessageReceiver connected = userSession.connected(fixMessageValue, appWriter);
        appMessageReceiver = new AppMessageReceiver() {
            @Override
            public void messages(FixMessageValue fixMessageValue) {
                try {
                    connected.messages(fixMessageValue);
                } catch (Exception exception) {
                    log.error("Unexpected user session exception : " + exception.getMessage(), exception);
                }
            }
        };
    }

    private void consumeSeqNum() {
        expectedNext = clientSeqMsgId.next(expectedNext);
    }

    abstract class AbstractSessionState implements SessionState {
        @Override
        public SessionState logon(int seqNum, FixMessageValue fixMessageValue) {
            return this;
        }

        @Override
        public SessionState logout(int seqNum, FixMessageValue fixMessageValue) {
            consumeSeqNum();
            sendLogout("Requested");
            shutdown();
            return new LogoutSessionState();
        }

        @Override
        public SessionState checkSeqNum(int seqNum, FixMessageValue fixMessageValue) {
            // FIX spec : bypass check seq num
            final Glob message = fixMessageValue.message();
            if (message != null && message.getType() == SequenceResetType.TYPE
                && !message.isTrue(SequenceResetType.gapFillFlag)) {
                return this;
            }
            if (seqNum < expectedNext) {
                if (fixMessageValue.header().isTrue(headerDesc.isDup())) {
                    return managePastDuplicate(seqNum, fixMessageValue);
                }
                // FIX spec : MsgSeqNum lower than expected without PossDupFlag is a serious error
                // => send a Logout with the cause and disconnect.
                final String msg = "MsgSeqNum too low, expecting " + expectedNext + " but received " + seqNum;
                log.error(ident + " " + msg);
                sendLogout(msg);
                shutdown();
                // ignore the message being dispatched and end in logout state
                return new IgnoreAndReturnPrevious(new LogoutSessionState());
            }
            if (expectedNext != seqNum) {
                log.warn(ident + " Gap detected '" + expectedNext + "' was expected but got '" + seqNum + "'");
                return gapState(seqNum);
            }
            return this;
        }

        // the message is ignored : it must not reach the application nor consume a seqNum
        SessionState managePastDuplicate(int seqNum, FixMessageValue fixMessageValue) {
            final Glob message = fixMessageValue.message();
            if (message != null && message.getType() == SequenceResetType.TYPE) {
                final int newSeqNum = message.get(SequenceResetType.newSeqNo);
                if (newSeqNum > expectedNext) {
                    log.warn(ident + " past sequence reset with future seq : reset seqNum to " + newSeqNum);
                    expectedNext = clientSeqMsgId.reset(newSeqNum - 1);
                } else {
                    log.warn(ident + " duplicate ignored.");
                }
            } else {
                log.info(ident + " duplicate messages ignored.");
            }
            return new IgnoreAndReturnPrevious(this);
        }

        @Override
        public SessionState sequenceReset(int seqNum, FixMessageValue fixMessageValue) {
            final boolean gapFillFlag = fixMessageValue.message().isTrue(SequenceResetType.gapFillFlag);
            if (gapFillFlag) {
                consumeSeqNum();
                expectedNext = clientSeqMsgId.reset(fixMessageValue.message().get(SequenceResetType.newSeqNo) - 1);
            } else {
                expectedNext = clientSeqMsgId.reset(fixMessageValue.message().get(SequenceResetType.newSeqNo) - 1);
            }
            return this;
        }

        @Override
        public SessionState rejectedMessage(int seqNum, FixMessageValue fixMessageValue) {
            consumeSeqNum();
            return this;
        }

        @Override
        public SessionState appMessage(int seqNum, FixMessageValue fixMessageValue) {
            consumeSeqNum();
            return this;
        }


        @Override
        public SessionState heartBeat(int seqNum, FixMessageValue fixMessageValue) {
            consumeSeqNum();
            treatHeartBeat(fixMessageValue);
            return this;
        }

        @Override
        public SessionState resendRequest(int seqNum, FixMessageValue fixMessageValue) {
            consumeSeqNum();
            treatReSend(fixMessageValue);
            return this;
        }

        @Override
        public SessionState testRequest(int seqNum, FixMessageValue fixMessageValue) {
            consumeSeqNum();
            treatTestRequest(fixMessageValue);
            return this;
        }

        abstract public SessionState gapState(int seqNum);

        private class IgnoreAndReturnPrevious implements SessionState {
            private final SessionState previous;

            public IgnoreAndReturnPrevious(SessionState previous) {
                log.info(ident + " new state IgnoreAndReturnPrevious");
                this.previous = previous;
            }

            @Override
            public SessionState logon(int seqNum, FixMessageValue fixMessageValue) {
                log.info(ident + " new state back to " + previous);
                return previous;
            }

            @Override
            public SessionState logout(int seqNum, FixMessageValue fixMessageValue) {
                log.info(ident + " new state back to " + previous);
                return previous;
            }

            @Override
            public SessionState sequenceReset(int seqNum, FixMessageValue fixMessageValue) {
                log.info(ident + " new state back to " + previous);
                return previous;
            }

            @Override
            public SessionState rejectedMessage(int seqNum, FixMessageValue fixMessageValue) {
                log.info(ident + " new state back to " + previous);
                return previous;
            }

            @Override
            public SessionState appMessage(int seqNum, FixMessageValue fixMessageValue) {
                log.info(ident + " new state back to " + previous);
                return previous;
            }

            @Override
            public SessionState checkSeqNum(int seqNum, FixMessageValue fixMessageValue) {
                throw new RuntimeException("Bug : should not be called");
            }

            @Override
            public SessionState heartBeat(int seqNum, FixMessageValue fixMessageValue) {
                log.info(ident + " new state back to " + previous);
                return previous;
            }

            @Override
            public SessionState resendRequest(int seqNum, FixMessageValue fixMessageValue) {
                log.info(ident + " new state back to " + previous);
                return previous;
            }

            @Override
            public SessionState testRequest(int seqNum, FixMessageValue fixMessageValue) {
                log.info(ident + " new state back to " + previous);
                return previous;
            }

            @Override
            public String toString() {
                return "IgnoreAndReturnPrevious";
            }
        }
    }

    private void sendLogout(String msg) {
        try {
            userSession.logout().get(1, TimeUnit.SECONDS);
        } catch (Exception _) {
        }
        final MutableGlob header = FixSessionImpl.this.header.duplicate();
        writer.write(header, LogoutType.create(msg), null, false);
        closed = true;
    }

    private void sendReject(int seqNum, String msgType, String msg) {
        writer.write(FixSessionImpl.this.header.duplicate(), RejectType.create(seqNum, msgType, msg), null, false);
    }

    class InitiatorSessionState extends AbstractSessionState {
        private final AtomicBoolean logon = new AtomicBoolean(false);
        private Set<Integer> logonSendId = new HashSet<>();

        public InitiatorSessionState() {
            log.info(ident + " new state is InitiatorSessionState");

            final int seqNum = sentLogon();
            logonSendId.add(seqNum);
        }

        @Override
        public SessionState logon(int seqNum, FixMessageValue fixMessageValue) {
            logon.set(true);
            consumeSeqNum();
            connect(fixMessageValue);
            managedInHeartBeat(fixMessageValue);
            return new ConnectedSessionState();
        }

        @Override
        public SessionState logout(int seqNum, FixMessageValue fixMessageValue) {
            consumeSeqNum();
            sendLogout("Requested from initiator");
            shutdown();
            return new LogoutSessionState();
        }

        @Override
        public SessionState checkSeqNum(int seqNum, FixMessageValue fixMessageValue) {
            return super.checkSeqNum(seqNum, fixMessageValue);
        }

        @Override
        public SessionState rejectedMessage(int seqNum, FixMessageValue fixMessageValue) {
            consumeSeqNum();
            final int rejectedSeqNum = fixMessageValue.message().get(RejectType.refSeqNum);
            if (logonSendId.contains(rejectedSeqNum)) {
                log.warn(ident + " logon was rejected, force logout");
                shutdown();
            }
            return this;
        }

        @Override
        public SessionState appMessage(int seqNum, FixMessageValue fixMessageValue) {
            consumeSeqNum();
            if (fixMessageValue.header().isTrue(headerDesc.isDup())) {
                return this;
            }
            throw new RuntimeException("Unexpected app message before logon complete");
        }

        @Override
        public synchronized SessionState gapState(int seqNum) {
            endSelf();
            return new WaitForLogonGapSessionState(seqNum);
        }

        private void endSelf() {
        }

        @Override
        public String toString() {
            return "InitiatorSessionState";
        }

    }

    private class WaitForLogonGapSessionState implements SessionState {
        private int logonCount;
        protected final int requestSendSeqNum;
        protected final int firstReceivedSeqNum;
        protected int nextExpectedFuturSeqNum;
        protected int nextExpectedPastSeqNum;
        protected List<FixMessageValue> futureAppMessage = new ArrayList<>();
        protected List<FixMessageValue> pastAppMessage = new ArrayList<>();
        protected int firstGapInGap = -1;

        public WaitForLogonGapSessionState(int firstReceivedSeqNum) {
            this.nextExpectedPastSeqNum = expectedNext;
            this.firstReceivedSeqNum = firstReceivedSeqNum;
            log.info(ident + " New state is WaitForLogonGapSessionState :  [GAP] send ResendRequest from " + expectedNext + " to " + (firstReceivedSeqNum - 1));
            final MutableGlob header = FixSessionImpl.this.header.duplicate();
            writer.write(header, ResendRequestType.create(expectedNext, firstReceivedSeqNum - 1), null, false);
            requestSendSeqNum = header.get(headerDesc.seqNumField());
            nextExpectedFuturSeqNum = firstReceivedSeqNum + 1; // the message is received
        }

        @Override
        public SessionState logon(int seqNum, FixMessageValue fixMessageValue) {
            managedInHeartBeat(fixMessageValue);
            consume(seqNum);
            connect(fixMessageValue);
            for (FixMessageValue messageValue : pastAppMessage) {
                appMessageReceiver.messages(messageValue);
                final int current = messageValue.header().get(headerDesc.seqNumField());
                expectedNext = clientSeqMsgId.reset(current);
            }
            if (nextExpectedPastSeqNum == firstReceivedSeqNum) {
                for (FixMessageValue messageValue : futureAppMessage) {
                    appMessageReceiver.messages(messageValue);
                    final int current = messageValue.header().get(headerDesc.seqNumField());
                    expectedNext = clientSeqMsgId.reset(current);
                }
                expectedNext = clientSeqMsgId.reset(nextExpectedFuturSeqNum - 1);
                return new ConnectedSessionState();
            }
            return new ConnectedGapSessionState(this.requestSendSeqNum,
                    firstReceivedSeqNum, this.nextExpectedFuturSeqNum, this.futureAppMessage, this.firstGapInGap);
        }

        @Override
        public SessionState logout(int seqNum, FixMessageValue fixMessageValue) {
            log.info(ident + " logout received");
            consume(seqNum);
            sendLogout("Requested from unconnected gap");
            shutdown();
            return new LogoutSessionState();
        }

        @Override
        public SessionState heartBeat(int seqNum, FixMessageValue fixMessageValue) {
            consume(seqNum);
            treatHeartBeat(fixMessageValue);
            return this;
        }

        @Override
        public SessionState resendRequest(int seqNum, FixMessageValue fixMessageValue) {
            consume(seqNum);
            treatReSend(fixMessageValue);
            return this;
        }

        @Override
        public SessionState testRequest(int seqNum, FixMessageValue fixMessageValue) {
            consume(seqNum);
            treatTestRequest(fixMessageValue);
            return this;
        }

        @Override
        public SessionState sequenceReset(int seqNum, FixMessageValue fixMessageValue) {
            final int newSeqNum = fixMessageValue.message().get(SequenceResetType.newSeqNo);
            if (seqNum == nextExpectedFuturSeqNum) {
                nextExpectedPastSeqNum = newSeqNum;
            } else if (seqNum == nextExpectedPastSeqNum) {
                nextExpectedPastSeqNum = newSeqNum;
            } else {
                if (newSeqNum > nextExpectedFuturSeqNum) {
                    nextExpectedFuturSeqNum = newSeqNum;
                } else if (newSeqNum < firstReceivedSeqNum && newSeqNum > nextExpectedPastSeqNum) {
                    nextExpectedPastSeqNum = newSeqNum;
                }
            }
            return this;
        }

        @Override
        public SessionState rejectedMessage(int seqNum, FixMessageValue fixMessageValue) {
            consume(seqNum);
            if (requestSendSeqNum == fixMessageValue.message().get(RejectType.refSeqNum)) {
                log.error(ident + " resend request refused.");
                //??
            }
            return this;
        }

        void consume(int seqNum) {
            if (seqNum == nextExpectedFuturSeqNum) {
                nextExpectedFuturSeqNum++;
            } else if (seqNum == nextExpectedPastSeqNum) {
                nextExpectedPastSeqNum++;
            } else {
                log.warn(ident + " (unconnected) can not consume " + seqNum + " expectedPast is " + nextExpectedPastSeqNum + " expectedFutur is " + nextExpectedFuturSeqNum);
            }
        }

        @Override
        public SessionState appMessage(int seqNum, FixMessageValue fixMessageValue) {
            if (seqNum == nextExpectedFuturSeqNum) {
                futureAppMessage.add(fixMessageValue);
                nextExpectedFuturSeqNum++;
                return this;
            }
            if (seqNum == nextExpectedPastSeqNum) {
                pastAppMessage.add(fixMessageValue);
                nextExpectedPastSeqNum++;
                return this;
            }
            log.warn(ident + " at " + seqNum + ", app message ignored ");
            return this;
        }

        @Override
        public SessionState checkSeqNum(int seqNum, FixMessageValue fixMessageValue) {
            if (seqNum < nextExpectedPastSeqNum) {
                log.warn(ident + " unexpected past seqNum " + seqNum);
            }
            if (seqNum > nextExpectedFuturSeqNum) {
                log.warn(ident + " unexpected futur seqNum " + seqNum + " gap in gap");
                if (firstGapInGap == -1) {
                    firstGapInGap = seqNum;
                }
            }
            if (seqNum == nextExpectedFuturSeqNum) {
                return this;
            }
            if (seqNum == nextExpectedPastSeqNum) {
                return this;
            }
            log.warn(ident + " gap in gap in past.");
            return this;
        }

        @Override
        public String toString() {
            return "WaitForLogonGapSessionState";
        }

    }

    private class ConnectedSessionState extends AbstractSessionState {

        public ConnectedSessionState() {
            log.info(ident + " new state ConnectedSessionState");

        }

        @Override
        public SessionState logon(int seqNum, FixMessageValue fixMessageValue) {
            consumeSeqNum();
            return this;
        }

        @Override
        public SessionState appMessage(int seqNum, FixMessageValue fixMessageValue) {
            appMessageReceiver.messages(fixMessageValue);
            consumeSeqNum();
            return this;
        }

        @Override
        public SessionState gapState(int seqNum) {
            return new ConnectedGapSessionState(seqNum);
        }

        @Override
        public String toString() {
            return "ConnectedSessionState";
        }

    }

    private class ConnectedGapSessionState implements SessionState {
        protected final int requestSendSeqNum;
        protected final int firstReceivedSeqNum;
        protected int nextExpectedFuturSeqNum;
        protected List<FixMessageValue> futureAppMessage = new ArrayList<>();
        protected int firstGapInGap = -1;

        public ConnectedGapSessionState(int firstReceivedSeqNum) {
            this.firstReceivedSeqNum = firstReceivedSeqNum;
            log.info(ident + " new state : ConnectedGapSessionState ; [GAP] send ResendRequest from " + expectedNext + " to " + (firstReceivedSeqNum - 1));
            final MutableGlob header = FixSessionImpl.this.header.duplicate();
            writer.write(header, ResendRequestType.create(expectedNext, firstReceivedSeqNum - 1), null, false);
            requestSendSeqNum = header.get(headerDesc.seqNumField());
            nextExpectedFuturSeqNum = firstReceivedSeqNum;
        }

        public ConnectedGapSessionState(int requestSendSeqNum, int firstReceivedSeqNum, int nextExpectedFuturSeqNum,
                                        List<FixMessageValue> futureAppMessage, int firstGapInGap) {
            log.info(ident + " new state : ConnectedGapSessionState (follow logon) ; [GAP] " + expectedNext + " to " + (firstReceivedSeqNum - 1));
            this.requestSendSeqNum = requestSendSeqNum;
            this.firstReceivedSeqNum = firstReceivedSeqNum;
            this.nextExpectedFuturSeqNum = nextExpectedFuturSeqNum;
            this.firstGapInGap = firstGapInGap;
            this.futureAppMessage.addAll(futureAppMessage);
        }

        @Override
        public SessionState logon(int seqNum, FixMessageValue fixMessageValue) {
            if (seqNum == expectedNext) {
                consume(seqNum);
                return checkGapComplete(seqNum);
            }
            consume(seqNum);
            return this;
        }

        @Override
        public SessionState logout(int seqNum, FixMessageValue fixMessageValue) {
            log.info(ident + " logout received");
            consume(seqNum);
            sendLogout("Requested from gap");
            shutdown();
            return new LogoutSessionState();
        }

        @Override
        public SessionState heartBeat(int seqNum, FixMessageValue fixMessageValue) {
            consume(seqNum);
            treatHeartBeat(fixMessageValue);
            return this;
        }

        @Override
        public SessionState resendRequest(int seqNum, FixMessageValue fixMessageValue) {
            consume(seqNum);
            treatReSend(fixMessageValue);
            return this;
        }

        @Override
        public SessionState testRequest(int seqNum, FixMessageValue fixMessageValue) {
            consume(seqNum);
            treatTestRequest(fixMessageValue);
            return this;
        }

        @Override
        public SessionState sequenceReset(int seqNum, FixMessageValue fixMessageValue) {
            final Glob message = fixMessageValue.message();
            final int newSeqNum = message.get(SequenceResetType.newSeqNo);
            if (message.isTrue(SequenceResetType.gapFillFlag)) {
                if (newSeqNum > nextExpectedFuturSeqNum) {
                    expectedNext = firstReceivedSeqNum; // not real update of next expected : updated to force to connected state
                    nextExpectedFuturSeqNum = newSeqNum;
                } else if (newSeqNum <= firstReceivedSeqNum && newSeqNum > expectedNext) {
                    expectedNext = clientSeqMsgId.reset(newSeqNum - 1);
                }
                consume(seqNum);
                return checkGapComplete(seqNum);
            } else {
                expectedNext = clientSeqMsgId.reset(newSeqNum - 1);
                return new ConnectedSessionState();
            }
        }

        private SessionState checkGapComplete(int seqNum) {
            if (expectedNext >= firstReceivedSeqNum) {
                for (FixMessageValue messageValue : futureAppMessage) {
                    final int current = messageValue.header().get(headerDesc.seqNumField());
                    appMessageReceiver.messages(messageValue);
                    expectedNext = clientSeqMsgId.reset(current);
                }
                if (seqNum >= expectedNext) {
                    expectedNext = clientSeqMsgId.reset(seqNum);
                }
                if (firstGapInGap != -1) {
                    log.info(ident + " gap in gap detected at " + firstGapInGap + " requesting resend.");
                    return new ConnectedGapSessionState(firstGapInGap);
                }
                return new ConnectedSessionState();
            }
            return this;
        }

        @Override
        public SessionState rejectedMessage(int seqNum, FixMessageValue fixMessageValue) {
            consume(seqNum);
            if (requestSendSeqNum == fixMessageValue.message().get(RejectType.refSeqNum)) {
                log.error(ident + " resend request refused.");
                //??
            }
            return this;
        }

        void consume(int seqNum) {
            if (seqNum == expectedNext) {
                expectedNext = clientSeqMsgId.next(expectedNext);
            } else if (seqNum >= nextExpectedFuturSeqNum) {
                nextExpectedFuturSeqNum = seqNum + 1;
            } else {
                log.warn(ident + " (connected) can not consume " + seqNum + " expectedNext " + expectedNext + " nextExpectedFuturSeqNum " + nextExpectedFuturSeqNum);
            }
        }

        @Override
        public SessionState appMessage(int seqNum, FixMessageValue fixMessageValue) {
            if (seqNum == nextExpectedFuturSeqNum) {
                futureAppMessage.add(fixMessageValue);
                nextExpectedFuturSeqNum++;
                return this;
            }
            if (seqNum == expectedNext) {
                appMessageReceiver.messages(fixMessageValue);
                consume(seqNum);
                return checkGapComplete(seqNum);
            }
            if (seqNum > expectedNext && seqNum < firstReceivedSeqNum) {
                log.info(ident + " message " + seqNum + " received during gap (gap in gap)");
            }
            log.warn(ident + " at " + seqNum + ", app message ignored expected " + expectedNext + " firstReceivedSeqNum " + firstReceivedSeqNum + " nextExpectedFuturSeqNum " + nextExpectedFuturSeqNum);
            return this;
        }

        @Override
        public SessionState checkSeqNum(int seqNum, FixMessageValue fixMessageValue) {
            if (seqNum < expectedNext) {
                if (fixMessageValue.header().isTrue(headerDesc.isDup())) {
                    log.info(ident + " duplicate messages ignored.");
                } else {
                    log.warn(ident + " unexpected past seqNum " + seqNum);
                }
            }
            if (seqNum > nextExpectedFuturSeqNum) {
                log.warn(ident + " unexpected futur seqNum " + seqNum + " gap in gap");
                if (firstGapInGap == -1) {
                    firstGapInGap = seqNum;
                }
            }
            return this;
        }

        @Override
        public String toString() {
            return "ConnectedGapSessionState";
        }
    }

    private class LogoutSessionState extends AbstractSessionState {
        public LogoutSessionState() {
            log.info(ident + " new state is LogoutSessionState");
        }

        @Override
        public SessionState logon(int seqNum, FixMessageValue fixMessageValue) {
            return reject(seqNum, fixMessageValue);
        }

        @Override
        public SessionState logout(int seqNum, FixMessageValue fixMessageValue) {
            return reject(seqNum, fixMessageValue);
        }

        @Override
        public SessionState sequenceReset(int seqNum, FixMessageValue fixMessageValue) {
            return reject(seqNum, fixMessageValue);
        }


        @Override
        public SessionState rejectedMessage(int seqNum, FixMessageValue fixMessageValue) {
            return reject(seqNum, fixMessageValue);
        }

        @Override
        public SessionState appMessage(int seqNum, FixMessageValue fixMessageValue) {
            return reject(seqNum, fixMessageValue);
        }

        @Override
        public SessionState gapState(int seqNum) {
            return this;
        }

        @Override
        public SessionState checkSeqNum(int seqNum, FixMessageValue fixMessageValue) {
            if (expectedNext != seqNum) {
                log.info(ident + " receive message in logout statue with bad seqNum " + seqNum + " vs expected " + expectedNext + " : " + fixMessageValue);
            }
            return this;
        }

        private SessionState reject(int seqNum, FixMessageValue fixMessageValue) {
            consumeSeqNum();
            sendReject(seqNum, fixMessageValue.header().get(headerDesc.msgType()), "logout request");
            return this;
        }

        @Override
        public String toString() {
            return "LogoutSessionState";
        }

    }

    // special behavior : we only expect logon message
    // we ignore expected next in checkSeqNum
    class AcceptorSessionState implements SessionState {
        public AcceptorSessionState() {
            log.info(ident + " new state is AcceptorSessionState");
        }

        @Override
        public SessionState logon(int seqNum, FixMessageValue fixMessageValue) {
            connect(fixMessageValue);
            managedInHeartBeat(fixMessageValue);
            sentLogon();
            if (seqNum != expectedNext) {
                final ConnectedGapSessionState connectedGapSessionState = new ConnectedGapSessionState(seqNum);
                return connectedGapSessionState.logon(seqNum, fixMessageValue); // special case where we return a gap state in a change stete not in seqNumCheck
            } else {
                consumeSeqNum();
                return new ConnectedSessionState();
            }
        }

        @Override
        public SessionState logout(int seqNum, FixMessageValue fixMessageValue) {
            return cancel();
        }

        @Override
        public SessionState sequenceReset(int seqNum, FixMessageValue fixMessageValue) {
            return cancel();
        }

        @Override
        public SessionState rejectedMessage(int seqNum, FixMessageValue fixMessageValue) {
            return cancel();
        }

        SessionState cancel() {
            shutdown();
            return new LogoutSessionState();
        }

        @Override
        public SessionState appMessage(int seqNum, FixMessageValue fixMessageValue) {
            return cancel();
        }

        @Override
        public SessionState checkSeqNum(int seqNum, FixMessageValue fixMessageValue) {
            return this;
        }

        @Override
        public SessionState heartBeat(int seqNum, FixMessageValue fixMessageValue) {
            return cancel();
        }

        @Override
        public SessionState resendRequest(int seqNum, FixMessageValue fixMessageValue) {
            return cancel();
        }

        @Override
        public SessionState testRequest(int seqNum, FixMessageValue fixMessageValue) {
            return cancel();
        }

        @Override
        public String toString() {
            return "AcceptorSessionState";
        }

    }

    public CompletableFuture<Boolean> logout() {
        synchronized (sessionLock) {
            if (closed || userSession == null) {
                // logout already sent or session already shut down
                return closedCompletable;
            }
            final CompletableFuture<Void> logout = userSession.logout();
            try {
                if (logout != null) {
                    logout.get(1, TimeUnit.SECONDS);
                }
            } catch (Exception _) {
            }
            closed = true;
            sessionState = new LogoutSessionExpectingLogout();
            writer.write(FixSessionImpl.this.header.duplicate(),
                    LogoutType.create("Logout requested."), null, false);

            // add async call to close in case no response are sent : shutdown completes closedCompletable
            scheduledExecutorService.schedule(this::shutdown, option.delayBeforeForceLogout(), TimeUnit.SECONDS);
            return closedCompletable;
        }
    }

    private int sentLogon() {
        final MutableGlob logon = userSession.getLogon().duplicate();
        heartbeatInMSOut = logon.get(LogonType.heartBtInt, DELAY_BETWEEN_CONNECT_AND_LOGON) * 1000L;
        log.info(ident + ": heartbeat to send " + heartbeatInMSOut + "ms");
        scheduleOut = scheduledExecutorService.schedule(this::manageOutHeartBeat, heartbeatInMSOut, TimeUnit.MILLISECONDS);

        final int current = clientSeqMsgId.current();
        if (current != 0) {
            logon.set(LogonType.nextExpectedMsgSeqNum, current + 1);
        }
        final MutableGlob header = FixSessionImpl.this.header.duplicate();
        writer.write(header, logon, null, false);
        return header.get(headerDesc.seqNumField());
    }

    void treatTestRequest(FixMessageValue fixMessageValue) {
        final String testReqId = fixMessageValue.message().get(TestRequestType.testReqID);
        writer.write(FixSessionImpl.this.header.duplicate(), HeartbeatType.create(testReqId), null, false);
    }

    void treatHeartBeat(FixMessageValue fixMessageValue) {
        final String s = fixMessageValue.message().get(HeartbeatType.testReqID);
        if (expectedHeartbeat != null) {
            if (expectedHeartbeat.equals(s)) {
                expectedHeartbeat = null;
            } else {
                log.warn(ident + ": Unexpected heartbeat: " + s);
            }
        }
    }

    private void shutdown() {
        synchronized (sessionLock) {
            if (userSession == null) {
                return;
            }
            userSession = null;
            closed = true;
            if (scheduleOut != null) {
                scheduleOut.cancel(false);
            }
            if (scheduleIn != null) {
                scheduleIn.cancel(false);
            }
        }
        shutdown.close();
        // no-op if already completed : make sure onClose runnables always run whatever the termination path
        closedCompletable.complete(false);
    }

    private void treatReSend(FixMessageValue fixMessageValue) {
        final Integer beginSeq = fixMessageValue.message().get(ResendRequestType.beginSeqNo);
        final int endSeq = fixMessageValue.message().get(ResendRequestType.endSeqNo, 0);
        final FixMessage[] data = fixMessageRepository.get(beginSeq, endSeq);
        if (data != null && data.length > 0) {
            int gapfill = -1;
            for (FixMessage d : data) {
                if (FixAdminModel.TYPES.contains(d.getBody().getType())) {
                    if (gapfill == -1) {
                        gapfill = d.getHeader().get(headerDesc.seqNumField()); // (A) seqnum du 1er message admin sauté
                    }
                } else {
                    if (gapfill != -1) {
                        final MutableGlob h = FixSessionImpl.this.header.duplicate()
                                .set(headerDesc.isDup(), true)
                                .set(headerDesc.seqNumField(), gapfill);
                        writer.write(h,
                                SequenceResetType.create(true, d.getHeader().get(headerDesc.seqNumField())),
                                null, false); // (B) NewSeqNo = seqNum original du 1er message applicatif rejoué
                        gapfill = -1;
                    }
                    writer.write(FixSessionImpl.this.header.duplicate()
                                    .set(headerDesc.isDup(), true)
                                    .set(headerDesc.seqNumField(), d.getHeader().get(headerDesc.seqNumField()))
                                    .set(headerDesc.origSendingTime(), d.getHeader().get(headerDesc.sendingTime()))
                            , d.getBody(), d.getTrailer(), false);
                }
            }
            if (gapfill != -1) {
                final MutableGlob h = FixSessionImpl.this.header.duplicate()
                        .set(headerDesc.isDup(), true)
                        .set(headerDesc.seqNumField(), gapfill);
                writer.write(h,
                        SequenceResetType.create(true, endSeq == 0 ? clientSeqMsgId.current() + 1 : endSeq + 1),
                        null, false); // send GapFill for trailing admin messages
            }
        } else {
            final int newSeqNo = endSeq == 0 ? clientSeqMsgId.current() + 1 : endSeq + 1;
            log.info(ident + " [resend] reset to end " + (newSeqNo));
            final MutableGlob h = FixSessionImpl.this.header.duplicate()
                    .set(headerDesc.isDup(), true)
                    .set(headerDesc.seqNumField(), beginSeq);
            writer.write(h,
                    SequenceResetType.create(true, newSeqNo), null, false);
        }
    }

    private void managedInHeartBeat(FixMessageValue fixLogon) {
        final Glob logon = fixLogon.message();
        heartbeatInMSIn = logon.get(LogonType.heartBtInt, DELAY_BETWEEN_CONNECT_AND_LOGON) * 1000L;
        log.info(ident + ": expected heartbeat every " + heartbeatInMSIn + "ms");
        scheduleIn = scheduledExecutorService.schedule(this::manageInHeartBeat, heartbeatInMSIn - 100, TimeUnit.MILLISECONDS);
    }

    private void manageInHeartBeat() {
        synchronized (sessionLock) {
            if (userSession == null) {
                return;
            }
            long when = (lastMessageReceivedTimeStampInMS + heartbeatInMSIn) - System.currentTimeMillis() + (heartbeatInMSIn * 15) / 100;
            if (when <= 0) {
                log.info(ident + ": send heartbeat request");
                expectedHeartbeat = UUID.randomUUID().toString();
                writer.write(FixSessionImpl.this.header.duplicate(), TestRequestType.create(expectedHeartbeat), null, false);
                scheduleIn = scheduledExecutorService.schedule(this::checkReceived, heartbeatInMSIn, TimeUnit.MILLISECONDS);
            } else {
                scheduleIn = scheduledExecutorService.schedule(this::manageInHeartBeat, when, TimeUnit.MILLISECONDS);
            }
        }
    }

    private void checkReceived() {
        synchronized (sessionLock) {
            if (userSession == null) {
                return;
            }
            if (expectedHeartbeat == null) {
                log.info(ident + ": Requested heartbeat was received and cleared");
                manageInHeartBeat();
            } else {
                if (System.currentTimeMillis() - lastMessageReceivedTimeStampInMS > heartbeatInMSIn) {
                    log.error(ident + ": Heartbeat not received " + expectedHeartbeat + ". Shutdown connection.");
                    shutdown();
                } else {
                    log.info(ident + ": A message was received, continue.");
                    manageInHeartBeat();
                }
            }
        }
    }

    private void manageOutHeartBeat() {
        synchronized (sessionLock) {
            if (userSession == null) {
                return;
            }
            long timeElapsedSinceWrite = System.currentTimeMillis() - lastWriteOut;
            long when = heartbeatInMSOut - timeElapsedSinceWrite - 100;
            if (when <= 0) {
                log.info(ident + ": send heartbeat");
                writer.write(FixSessionImpl.this.header.duplicate(), HeartbeatType.create(), null, false);
                when = heartbeatInMSOut;
            }
            when = Math.max(when, 100);
            scheduleOut = scheduledExecutorService.schedule(this::manageOutHeartBeat, when, TimeUnit.MILLISECONDS);
        }
    }

    private class AppFixWriter implements FixWriter {
        private final HeaderDesc headerDesc;

        public AppFixWriter(HeaderDesc headerDesc) {
            this.headerDesc = headerDesc;
        }

        @Override
        public void write(FixMessage message) {
            if (closed) {
                throw new RuntimeException("Session is closed");
            }
            final MutableGlob h = message.getHeader();
            h.unset(headerDesc.seqNumField()); // to prevent any bug on seqNum
            h.unset(headerDesc.sendingTime());
            writer.write(message);
        }
    }

    private class LogoutSessionExpectingLogout extends LogoutSessionState {

        public LogoutSessionExpectingLogout() {
            log.info(ident + " new state is LogoutSessionExpectingLogout");
        }

        @Override
        public SessionState logout(int seqNum, FixMessageValue fixMessageValue) {
            closedCompletable.complete(true);
            if (seqNum == expectedNext) {
                consumeSeqNum();
            } else {
                log.warn(ident + " invalid seqNum " + seqNum + " vs expected " + expectedNext);
            }
            return new LogoutSessionState();
        }

        @Override
        public String toString() {
            return "ExpectedLogoutSessionState";
        }
    }
}
