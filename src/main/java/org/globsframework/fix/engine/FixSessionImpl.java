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

public class FixSessionImpl implements FixMessageListener {
    private static final Logger log = LoggerFactory.getLogger(FixSessionImpl.class);
    public static final int DELAY_BETWEEN_CONNECT_AND_LOGON = 10;
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
    private long heartbeatInMSIn;
    private volatile long lastMessageReceivedTimeStampInMS = -1;
    private long heartbeatInMSOut;
    private volatile long lastWriteOut = -1;
    private String expectedHeartbeat;
    private ClientSeqMsgId clientSeqMsgId;
    private ScheduledFuture<?> scheduleOut;
    private ScheduledFuture<?> scheduleIn;
    private UserSession userSession;
    private AppMessageReceiver appMessageReceiver;
    private int expectedNext;
    private volatile boolean closed;
    private final Option option;
    private List<Runnable> onClose = new ArrayList<>();
    private CompletableFuture<Boolean> closedCompletable = new CompletableFuture<>();
    private SessionState sessionState;

    synchronized public void registerOnClosed(Runnable runnable) {
        if (closed) {
            runnable.run();
            return;
        }
        onClose.add(runnable);
    }

    public record Option(int delayBeforeForceLogout,
                         int delayBeforeResendLogonInS, int maxRetryLogon) {
        public static Option def() {
            return new Option(1, 1, 3);
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
            public void write(MutableGlob header, Glob message, MutableGlob trailer, boolean resetSeqNum) {
                lastWriteOut = System.currentTimeMillis();
                fixWriter.write(header, message, trailer, false);
                if (log.isDebugEnabled()) {
                    log.debug(identSend + " send : [" + jsonOut(header, false) + "," + jsonOut(message, true) + ", " +
                              jsonOut(trailer, false) + "], reset " + resetSeqNum);
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
        final Glob header = userSession.getHeader();
        final String senderCompId = header.get(headerDesc.senderCompIDField());
        final String targetCompId = header.get(headerDesc.targetCompIDField());
        ident = senderCompId + "-" + targetCompId;
        identSend = senderCompId + "->" + targetCompId;
        identReceive = senderCompId + "<-" + targetCompId;
        log.info(ident + " new session at " + selfMsgSeqProvider.current() + " and expected seqNum " + expectedNext + " from " + targetCompId);
        closedCompletable.whenComplete((aBoolean, throwable) -> {
            synchronized (FixSessionImpl.this) {
                for (Runnable runnable : onClose) {
                    try {
                        runnable.run();
                    } catch (Exception e) {
                        log.warn("Error thrown on close " + e.getMessage(), e);
                    }
                }
                onClose.clear();
            }
        });
        if (isInitiator) {
            sessionState = new InitiatorSessionState();
        } else {
            sessionState = new AcceptorSessionState();
        }
    }

    private static String jsonOut(Glob data, boolean withKind) {
        return data == null? "null" : GSonUtils.encode(data, withKind);
    }

    @Override
    public void newMessage(FixMessageValue fixMessageValue) {
        log.info(identReceive + " receive : [" + jsonOut(fixMessageValue.header(), false) + "," + jsonOut(fixMessageValue.message(), true) +
                 ", " + jsonOut(fixMessageValue.trailer(), false) + "]");

        lastMessageReceivedTimeStampInMS = System.currentTimeMillis();
        final int seqNum = fixMessageValue.header().get(headerDesc.seqNumField());
        sessionState = sessionState.checkSeqNum(seqNum, fixMessageValue);
        if (log.isInfoEnabled()) {
            log.info(ident + " state after checkSeqNum is " + sessionState.toString());
        }
        final Glob message = fixMessageValue.message();
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
        if (log.isInfoEnabled()) {
            log.info(ident +" state is " + sessionState.toString());
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
        appMessageReceiver = userSession.connected(fixMessageValue, appWriter);
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
            if (fixMessageValue.message().getType() == SequenceResetType.TYPE
                && !fixMessageValue.message().isTrue(SequenceResetType.gapFillFlag)) {
                return this;
            }
            if (seqNum < expectedNext) {
                if (fixMessageValue.header().isTrue(headerDesc.isDup())) {
                    log.info(ident + " duplicate messages ignored.");
                } else {
                    final String msg = ident + " invalid seq num " + seqNum + " expecting " + expectedNext;
                    log.error(msg);
                    // check
                    sendReject(seqNum, fixMessageValue.header().get(headerDesc.msgType()), msg);
                    SessionState previous = this;
                    // ignore the message and return to the previous state
                    return new SessionState() {
                        @Override
                        public SessionState logon(int seqNum, FixMessageValue fixMessageValue) {
                            return previous;
                        }

                        @Override
                        public SessionState logout(int seqNum, FixMessageValue fixMessageValue) {
                            return previous;
                        }

                        @Override
                        public SessionState sequenceReset(int seqNum, FixMessageValue fixMessageValue) {
                            return previous;
                        }

                        @Override
                        public SessionState rejectedMessage(int seqNum, FixMessageValue fixMessageValue) {
                            return previous;
                        }

                        @Override
                        public SessionState appMessage(int seqNum, FixMessageValue fixMessageValue) {
                            return previous;
                        }

                        @Override
                        public SessionState checkSeqNum(int seqNum, FixMessageValue fixMessageValue) {
                            throw new RuntimeException("Bug : should not be called");
                        }

                        @Override
                        public SessionState heartBeat(int seqNum, FixMessageValue fixMessageValue) {
                            return previous;
                        }

                        @Override
                        public SessionState resendRequest(int seqNum, FixMessageValue fixMessageValue) {
                            return previous;
                        }

                        @Override
                        public SessionState testRequest(int seqNum, FixMessageValue fixMessageValue) {
                            return previous;
                        }

                        @Override
                        public String toString() {
                            return "IgnoreSessionState";
                        }
                    };
                }
            }
            if (expectedNext != seqNum) {
               return manageGap(seqNum, fixMessageValue);
            }
            return this;
        }

        SessionState manageGap(int seqNum, FixMessageValue fixMessageValue) {
            log.warn(ident + " Gap detected '" + expectedNext + "' was expected but got '" + seqNum + "'");
            if (seqNum < expectedNext) {
                if (fixMessageValue.message().getType() == SequenceResetType.TYPE) {
                    final int newSeqNum = fixMessageValue.message().get(SequenceResetType.newSeqNo);
                    if (newSeqNum > expectedNext) {
                        log.warn(ident + " past sequence reset with future seq : reset seqNum to " + newSeqNum);
                        expectedNext = clientSeqMsgId.reset(newSeqNum - 1);
                    } else {
                        log.warn(ident + " duplicate ignored.");
                    }
                } else {
                    final boolean isDup = fixMessageValue.header().isTrue(headerDesc.isDup());
                    if (isDup) {
                        log.warn(ident + " ignore old message.");
                    } else {
                        log.error(ident + " received message.");
                    }
                }
                return this;
            } else {
                return gapState(seqNum);
            }
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
    }

    private void sendLogout(String msg) {
        try {
            userSession.logout().get(1, TimeUnit.SECONDS);
        } catch (Exception _) {
        }
        closed = true;
        final MutableGlob header = userSession.getHeader().duplicate();
        writer.write(header, LogoutType.create(msg), null, false);
    }

    private void sendReject(int seqNum, String msgType, String msg) {
        writer.write(userSession.getHeader().duplicate(), RejectType.create(seqNum, msgType, msg), null, false);
    }

    class InitiatorSessionState extends AbstractSessionState {
        private final AtomicBoolean logon = new AtomicBoolean(false);
        private Set<Integer> logonSendId = new HashSet<>();

        public InitiatorSessionState() {
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
            log.info(ident + " [GAP] send ResendRequest from " + expectedNext + " to " + (firstReceivedSeqNum - 1));
            final MutableGlob header = userSession.getHeader().duplicate();
            writer.write(header, ResendRequestType.create(expectedNext, firstReceivedSeqNum - 1), null, false);
            requestSendSeqNum = header.get(headerDesc.seqNumField());
            nextExpectedFuturSeqNum = firstReceivedSeqNum + 1; // the message is received
        }

        @Override
        public SessionState logon(int seqNum, FixMessageValue fixMessageValue) {
            managedInHeartBeat(fixMessageValue);
            consume(seqNum);
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
                connect(fixMessageValue);
                return new ConnectedSessionState();
            }
            connect(fixMessageValue);
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
            log.info(ident + " [GAP] send ResendRequest from " + expectedNext + " to " + (firstReceivedSeqNum - 1));
            final MutableGlob header = userSession.getHeader().duplicate();
            writer.write(header, ResendRequestType.create(expectedNext, firstReceivedSeqNum - 1), null, false);
            requestSendSeqNum = header.get(headerDesc.seqNumField());
            nextExpectedFuturSeqNum = firstReceivedSeqNum;
        }

        public ConnectedGapSessionState(int requestSendSeqNum, int firstReceivedSeqNum, int nextExpectedFuturSeqNum,
                                        List<FixMessageValue> futureAppMessage, int firstGapInGap) {

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
            }
            else {
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
        final CompletableFuture<Void> logout = userSession.logout();
        try {
            if (logout != null) {
                logout.get(1, TimeUnit.SECONDS);
            }
        } catch (Exception _) {
        }
        closed = true;
        sessionState = new LogoutSessionState() {

            @Override
            public SessionState logout(int seqNum, FixMessageValue fixMessageValue) {
                closedCompletable.complete(true);
                if (seqNum == expectedNext) {
                    consumeSeqNum();
                }
                else {
                    log.warn(ident + " invalid seqNum " + seqNum + " vs expected " + expectedNext);
                }
                return new LogoutSessionState();
            }

            @Override
            public String toString() {
                return "ExpectedLogoutSessionState";
            }
        };
        writer.write(userSession.getHeader().duplicate(),
                LogoutType.create("Logout requested."), null, false);

//        while (true) {
        // add async call to close in case no response are sent.
        scheduledExecutorService.schedule(() -> {
            closedCompletable.complete(false);
            shutdown();
        }, option.delayBeforeForceLogout(), TimeUnit.SECONDS);
        return closedCompletable;
    }

    private int sentLogon() {
        final MutableGlob logon = userSession.getLogon().duplicate();
        heartbeatInMSOut = logon.get(LogonType.heartBtInt, DELAY_BETWEEN_CONNECT_AND_LOGON) * 1000L;
        scheduleOut = scheduledExecutorService.schedule(this::manageOutHeartBeat, heartbeatInMSOut, TimeUnit.MILLISECONDS);

        final int current = clientSeqMsgId.current();
        if (current != 0) {
            logon.set(LogonType.nextExpectedMsgSeqNum, current + 1);
        }
        final MutableGlob header = userSession.getHeader().duplicate();
        writer.write(header, logon, null, false);
        return header.get(headerDesc.seqNumField());
    }

    void treatTestRequest(FixMessageValue fixMessageValue) {
        final String testReqId = fixMessageValue.message().get(TestRequestType.testReqID);
        writer.write(userSession.getHeader().duplicate(), HeartbeatType.create(testReqId), null, false);
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
        if (userSession == null) {
            return;
        }
        shutdown.close();
        if (scheduleOut != null) {
            scheduleOut.cancel(false);
        }
        if (scheduleIn != null) {
            scheduleIn.cancel(false);
        }
        userSession = null;
    }

    private void treatReSend(FixMessageValue message) {
        final Integer beginSeq = message.message().get(ResendRequestType.beginSeqNo);
        final int endSeq = message.message().get(ResendRequestType.endSeqNo, 0);
        final FixMessageRepository.FixRecoveredMessage[] data = fixMessageRepository.get(beginSeq, endSeq);
        if (data != null && data.length > 0) {
            int gapfill = -1;
            for (FixMessageRepository.FixRecoveredMessage d : data) {
                if (FixAdminModel.TYPES.contains(d.message().getType())) {
                    if (gapfill == -1) {
                        gapfill = d.header().get(headerDesc.seqNumField());
                    }
                } else {
                    if (gapfill != -1) {
                        final MutableGlob h = userSession.getHeader().duplicate()
                                .set(headerDesc.isDup(), true)
                                .set(headerDesc.seqNumField(), gapfill);
                        writer.write(h,
                                SequenceResetType.create(true, d.header().get(headerDesc.seqNumField())),
                                null, false); // send GapFill
                        gapfill = -1;
                    }
                    writer.write(d.header().duplicate()
                                    .set(headerDesc.isDup(), true)
                                    .set(headerDesc.origSendingTime(), d.header().get(headerDesc.sendingTime()))
                            , d.message(), d.trailer(), false);
                }
            }
            if (gapfill != -1) {
                final MutableGlob h = userSession.getHeader().duplicate()
                        .set(headerDesc.isDup(), true)
                        .set(headerDesc.seqNumField(), gapfill);
                writer.write(h,
                        SequenceResetType.create(true, endSeq == 0 ? clientSeqMsgId.current() + 1 : endSeq + 1),
                        null, false); // send GapFill for trailing admin messages
            }
        } else {
            final int newSeqNo = endSeq == 0 ? clientSeqMsgId.current() + 1 : endSeq + 1;
            log.info(ident + " [resend] reset to end " + (newSeqNo));
            final MutableGlob h = userSession.getHeader().duplicate()
                    .set(headerDesc.isDup(), true)
                    .set(headerDesc.seqNumField(), beginSeq);
            writer.write(h,
                    SequenceResetType.create(true, newSeqNo), null, false);
        }
    }

    private void managedInHeartBeat(FixMessageValue fixLogon) {
        final Glob logon = fixLogon.message();
        heartbeatInMSIn = logon.get(LogonType.heartBtInt, DELAY_BETWEEN_CONNECT_AND_LOGON) * 1000L;
        scheduleIn = scheduledExecutorService.schedule(this::manageInHeartBeat, heartbeatInMSIn - 100, TimeUnit.MILLISECONDS);
    }

    private void manageInHeartBeat() {
        if (userSession == null) {
            return;
        }
        long when =  (lastMessageReceivedTimeStampInMS + heartbeatInMSIn) - System.currentTimeMillis() + (heartbeatInMSIn * 15) / 100;
        if (when <= 0) {
            expectedHeartbeat = UUID.randomUUID().toString();
            writer.write(userSession.getHeader().duplicate(), TestRequestType.create(expectedHeartbeat), null, false);
            scheduleIn = scheduledExecutorService.schedule(this::checkReceived, heartbeatInMSIn, TimeUnit.MILLISECONDS);
        } else {
            scheduleIn = scheduledExecutorService.schedule(this::manageInHeartBeat, when, TimeUnit.MILLISECONDS);
        }
    }

    private void checkReceived() {
        if (userSession == null) {
            return;
        }
        if (expectedHeartbeat == null) {
            log.info(ident + ": Requested heartbeat was received and cleared");
            manageInHeartBeat();
        } else {
            if (System.currentTimeMillis() - lastMessageReceivedTimeStampInMS > heartbeatInMSIn) {
                log.error(ident + ": Heartbeat not received " + expectedHeartbeat + ". Shutdown connection.");
                shutdown.close();
            } else {
                log.info(ident + ": A message was received, continue.");
                manageInHeartBeat();
            }
        }
    }

    private void manageOutHeartBeat() {
        if (userSession == null) {
            return;
        }
        long timeElapsedSinceWrite = System.currentTimeMillis() - lastWriteOut;
        long when = heartbeatInMSOut - timeElapsedSinceWrite - 100;
        if (when <= 0) {
            writer.write(userSession.getHeader().duplicate(), HeartbeatType.create(), null, false);
            when = heartbeatInMSOut;
        }
        when = Math.max(when, 100);
        scheduleOut = scheduledExecutorService.schedule(this::manageOutHeartBeat, when, TimeUnit.MILLISECONDS);
    }

    private class AppFixWriter implements FixWriter {
        private final HeaderDesc headerDesc;

        public AppFixWriter(HeaderDesc headerDesc) {
            this.headerDesc = headerDesc;
        }

        @Override
        public void write(MutableGlob header, Glob message, MutableGlob trailer, boolean resetSeqNum) {
            if (closed) {
                throw new RuntimeException("Session is closed");
            }
            header.unset(headerDesc.seqNumField()); // to prevent any bug on seqNum
            writer.write(header, message, trailer, false);
        }
    }
}
