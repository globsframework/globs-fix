package org.globsframework.fix.engine;

import org.globsframework.core.metamodel.GlobType;
import org.globsframework.core.model.Glob;
import org.globsframework.core.model.MutableGlob;
import org.globsframework.fix.deserializer.FixMessageValue;
import org.globsframework.fix.dictionary.admin.*;
import org.globsframework.fix.serializer.FixWriter;
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
    private final ScheduledExecutorService scheduledExecutorService;
    private final FixWriter writer;
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
        }
        onClose.add(runnable);
    }

    public record Option(boolean resetSeqNumToOneOnGap, int delayBeforeResendLogonInS, int maxRetryLogon) {
        public static Option op(boolean resetSeqNumToOneOnGap) {
            return new Option(resetSeqNumToOneOnGap, 1, 3);
        }
    }

    public FixSessionImpl(ScheduledExecutorService scheduledExecutorService, FixWriter fixWriter,
                          UserSession userSession,
                          ClientSeqMsgId clientSeqMsgId,
                          FixMessageRepository fixMessageRepository, // intercept the fixWriter call to update, if needed, the data for replay.
                          HeaderDesc headerDesc,
                          Shutdown shutdown,
                          boolean isInitiator, Option option) {
        this.scheduledExecutorService = scheduledExecutorService;
        this.writer = new FixWriter() {
            @Override
            public void write(MutableGlob header, Glob message, MutableGlob trailer, boolean resetSeqNum) {
                lastWriteOut = System.currentTimeMillis();
                fixWriter.write(header, message, trailer, false);
            }
        };
        appWriter = new AppFixWriter(headerDesc);
        this.userSession = userSession;
        this.fixMessageRepository = fixMessageRepository;
        this.headerDesc = headerDesc;
        this.shutdown = shutdown;
        this.clientSeqMsgId = clientSeqMsgId;
        expectedNext = clientSeqMsgId.current() + 1;
        this.option = option;
        final Glob header = userSession.getHeader();
        ident = header.get(headerDesc.senderCompIDField()) + "-" +
                header.get(headerDesc.targetCompIDField());
        log.info(ident + " new session expected seq " + expectedNext);
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

    @Override
    public void newMessage(FixMessageValue fixMessageValue) {
        lastMessageReceivedTimeStampInMS = System.currentTimeMillis();
        final int seqNum = fixMessageValue.header().get(headerDesc.seqNumField());
        sessionState = sessionState.checkSeqNum(seqNum, fixMessageValue);
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
            if (seqNum < expectedNext) {
                if (fixMessageValue.header().isTrue(headerDesc.isDup())) {
                    log.info(ident + " duplicate messages ignored.");
                } else {
                    final String msg = ident + " invalid seq num " + seqNum + " expecting " + expectedNext;
                    log.error(msg);
                    sendReject(seqNum, fixMessageValue.header().get(headerDesc.msgType()), msg);
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
                        expectedNext = clientSeqMsgId.reset(newSeqNum);
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
            consumeSeqNum();
            final boolean gapFillFlag = fixMessageValue.message().isTrue(SequenceResetType.gapFillFlag);
            if (gapFillFlag) {
                expectedNext = fixMessageValue.message().get(SequenceResetType.newSeqNo);
            } else {
                expectedNext = fixMessageValue.message().get(SequenceResetType.newSeqNo);
            }
            clientSeqMsgId.reset(expectedNext);
            return this;
        }

        @Override
        public SessionState rejectedMessage(int seqNum, FixMessageValue fixMessageValue) {
            return this;
        }

        @Override
        public SessionState appMessage(int seqNum, FixMessageValue fixMessageValue) {
            return this;
        }


        @Override
        public SessionState heartBeat(int seqNum, FixMessageValue fixMessageValue) {
            treatHeartBeat(fixMessageValue);
            return this;
        }

        @Override
        public SessionState resendRequest(int seqNum, FixMessageValue fixMessageValue) {
            treatReSend(fixMessageValue);
            return this;
        }

        @Override
        public SessionState testRequest(int seqNum, FixMessageValue fixMessageValue) {
            treatTestRequest(fixMessageValue);
            return this;
        }

        abstract public SessionState gapState(int seqNum);
    }

    private void sendLogout(String msg) {
        writer.write(userSession.getHeader().duplicate(), LogoutType.create(msg), null, false);
    }

    private void sendReject(int seqNum, String msgType, String msg) {
        writer.write(userSession.getHeader().duplicate(), RejectType.create(seqNum, msgType, msg), null, false);
    }


    class InitiatorSessionState extends AbstractSessionState {
        private final ScheduledFuture<?> schedule;
        private final AtomicBoolean logon = new AtomicBoolean(false);
        private Set<Integer> logonSendId = new HashSet<>();
        int logonSendCount;

        public InitiatorSessionState() {
            final int seqNum = sentLogon();
            logonSendId.add(seqNum);
            if (option.delayBeforeResendLogonInS > 0) {
                logonSendCount = 1;
                schedule = scheduledExecutorService.schedule(this::retryLogon, option.delayBeforeResendLogonInS, TimeUnit.SECONDS);
            }
            else {
                logonSendCount = -1;
                schedule = null;
            }
        }

        @Override
        public SessionState logon(int seqNum, FixMessageValue fixMessageValue) {
            logon.set(true);
            if (schedule != null) {
                schedule.cancel(false);
            }
            consumeSeqNum();
            connect(fixMessageValue);
            managedInHeartBeat(fixMessageValue);
            return new ConnectedSessionState();
        }

        @Override
        public SessionState logout(int seqNum, FixMessageValue fixMessageValue) {
            consumeSeqNum();
            shutdown();
            return new LogoutSessionState();
        }

        @Override
        public SessionState rejectedMessage(int seqNum, FixMessageValue fixMessageValue) {
            final int rejectedSeqNum = fixMessageValue.message().get(RejectType.refSeqNum);
            if (logonSendId.contains(rejectedSeqNum)) {
                log.warn(ident + " logon was rejected, force logout");
                shutdown();
            }
            return this;
        }

        @Override
        public SessionState appMessage(int seqNum, FixMessageValue fixMessageValue) {
            if (fixMessageValue.header().isTrue(headerDesc.isDup())) {
                return this;
            }
            throw new RuntimeException("Unexpected app message before logon complete");
        }

        @Override
        public synchronized SessionState gapState(int seqNum) {
            endSelf();
            return new UnConnectedGapSessionState(logonSendCount, seqNum);
        }

        private void endSelf() {
            if (logonSendCount >= -1 && schedule != null) {
                logonSendCount = -1;
                schedule.cancel(false);
            }
        }

        synchronized private void retryLogon() {
            if (schedule.isCancelled()) {
                return;
            }
            if (logon.get()) {
                return;
            }
            if (option.maxRetryLogon == -1 || logonSendCount > option.maxRetryLogon) {
                shutdown();
            } else {
                int seqNum = sentLogon();
                logonSendId.add(seqNum);
                logonSendCount++;
            }
        }
    }

    private void connect(FixMessageValue fixMessageValue) {
        appMessageReceiver = userSession.connected(fixMessageValue, appWriter);
    }

    private void consumeSeqNum() {
        expectedNext = clientSeqMsgId.next(expectedNext);
    }

    private class UnConnectedGapSessionState implements SessionState {
        private int logonCount;
        private final ScheduledFuture<?> schedule;
        protected final int requestSendSeqNum;
        protected final int firstReceivedSeqNum;
        protected int nextExpectedFuturSeqNum;
        protected int nextExpectedPastSeqNum;
        protected List<FixMessageValue> futureAppMessage = new ArrayList<>();
        protected List<FixMessageValue> pastAppMessage = new ArrayList<>();
        protected int firstGapInGap = -1;

        public UnConnectedGapSessionState(int logonCount, int firstReceivedSeqNum) {
            if (option.delayBeforeResendLogonInS > 0 && logonCount > 0) {
                this.logonCount = logonCount;
                schedule = scheduledExecutorService.schedule(this::retryLogon, option.delayBeforeResendLogonInS, TimeUnit.SECONDS);
            } else {
                this.logonCount = -1;
                schedule = null;
            }
            this.firstReceivedSeqNum = firstReceivedSeqNum;
            log.info(ident + " [GAP] send ResendRequest from " + expectedNext + " to " + (firstReceivedSeqNum - 1));
            final MutableGlob header = userSession.getHeader().duplicate();
            writer.write(header, ResendRequestType.create(expectedNext, firstReceivedSeqNum - 1), null, false);
            requestSendSeqNum = header.get(headerDesc.seqNumField());
            nextExpectedFuturSeqNum = firstReceivedSeqNum + 1;
        }

        synchronized private void retryLogon() {
            if (logonCount == -1 || schedule.isCancelled()) {
                return;
            }
            if (option.maxRetryLogon == -1 || logonCount > option.maxRetryLogon) {
                shutdown();
            } else {
                sentLogon();
                logonCount++;
            }
        }

        @Override
        public SessionState logon(int seqNum, FixMessageValue fixMessageValue) {
            connect(fixMessageValue);
            managedInHeartBeat(fixMessageValue);
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
            return new LogoutSessionState();
        }

        @Override
        public SessionState heartBeat(int seqNum, FixMessageValue fixMessageValue) {
            treatHeartBeat(fixMessageValue);
            return this;
        }

        @Override
        public SessionState resendRequest(int seqNum, FixMessageValue fixMessageValue) {
            treatReSend(fixMessageValue);
            return this;
        }

        @Override
        public SessionState testRequest(int seqNum, FixMessageValue fixMessageValue) {
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
                log.warn(ident + " can not consume " + seqNum);
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
            }
            log.warn(ident + "app message ignored");
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
    }

    private class ConnectedSessionState extends AbstractSessionState {

        @Override
        public SessionState logon(int seqNum, FixMessageValue fixMessageValue) {
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
            nextExpectedFuturSeqNum = firstReceivedSeqNum + 1;
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
            return this;
        }

        @Override
        public SessionState logout(int seqNum, FixMessageValue fixMessageValue) {
            log.info(ident + " logout received");
            return new LogoutSessionState();
        }

        @Override
        public SessionState heartBeat(int seqNum, FixMessageValue fixMessageValue) {
            treatHeartBeat(fixMessageValue);
            return this;
        }

        @Override
        public SessionState resendRequest(int seqNum, FixMessageValue fixMessageValue) {
            treatReSend(fixMessageValue);
            return this;
        }

        @Override
        public SessionState testRequest(int seqNum, FixMessageValue fixMessageValue) {
            treatTestRequest(fixMessageValue);
            return this;
        }

        @Override
        public SessionState sequenceReset(int seqNum, FixMessageValue fixMessageValue) {
            final int newSeqNum = fixMessageValue.message().get(SequenceResetType.newSeqNo);
            if (seqNum == nextExpectedFuturSeqNum) {
                nextExpectedFuturSeqNum = newSeqNum;
            } else if (seqNum == expectedNext) {
                expectedNext = clientSeqMsgId.reset(newSeqNum);
            } else {
                if (newSeqNum > nextExpectedFuturSeqNum) {
                    expectedNext = firstReceivedSeqNum; // not real update of next expected : updated to force to connected state
                    nextExpectedFuturSeqNum = newSeqNum;
                } else if (newSeqNum < firstReceivedSeqNum && newSeqNum > expectedNext) {
                    expectedNext = clientSeqMsgId.reset(newSeqNum - 1);
                }
            }
            return checkGapComplete();
        }

        private SessionState checkGapComplete() {
            if (expectedNext >= firstReceivedSeqNum) {
                for (FixMessageValue messageValue : futureAppMessage) {
                    appMessageReceiver.messages(messageValue);
                    final int current = messageValue.header().get(headerDesc.seqNumField());
                    expectedNext = clientSeqMsgId.reset(current);
                }
                clientSeqMsgId.reset(nextExpectedFuturSeqNum - 1);
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
            if (seqNum == nextExpectedFuturSeqNum) {
                nextExpectedFuturSeqNum++;
            } else if (seqNum == expectedNext) {
                expectedNext = clientSeqMsgId.next(expectedNext);
            } else {
                log.warn(ident + " can not consume " + seqNum);
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
            }
            log.warn(ident + "app message ignored");
            return this;
        }

        @Override
        public SessionState checkSeqNum(int seqNum, FixMessageValue fixMessageValue) {
            if (seqNum < expectedNext) {
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
            if (seqNum == expectedNext) {
                return this;
            }
            log.warn(ident + " gap in gap in past.");
            return this;
        }
    }

    private class LogoutSessionState extends AbstractSessionState {
        @Override
        public SessionState logon(int seqNum, FixMessageValue fixMessageValue) {
            return reject();
        }

        @Override
        public SessionState logout(int seqNum, FixMessageValue fixMessageValue) {
            return reject();
        }

        @Override
        public SessionState sequenceReset(int seqNum, FixMessageValue fixMessageValue) {
            return reject();
        }


        @Override
        public SessionState rejectedMessage(int seqNum, FixMessageValue fixMessageValue) {
            return reject();
        }

        @Override
        public SessionState appMessage(int seqNum, FixMessageValue fixMessageValue) {
            return reject();
        }

        @Override
        public SessionState gapState(int seqNum) {
            return this;
        }

        @Override
        public SessionState checkSeqNum(int seqNum, FixMessageValue fixMessageValue) {
            return reject();
        }

        private SessionState reject() {
            throw new UnsupportedOperationException("Session is logout, refuse all messages.");
        }
    }

    class AcceptorSessionState implements SessionState {

        @Override
        public SessionState logon(int seqNum, FixMessageValue fixMessageValue) {
            connect(fixMessageValue);
            managedInHeartBeat(fixMessageValue);
            if (seqNum != expectedNext) {
                return new ConnectedGapSessionState(seqNum);
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
            if (fixMessageValue.message().getType() != LogonType.TYPE) {
                shutdown();
                return new LogoutSessionState();
            }
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
        writer.write(userSession.getHeader().duplicate(),
                LogoutType.create("Logout requested."), null, false);

//        while (true) {
        // add async call to close in case no response are sent.
        scheduledExecutorService.schedule(() -> {
            shutdown();
        }, 1, TimeUnit.SECONDS);
        return closedCompletable;
    }


    private int sentLogon() {
        final MutableGlob logon = userSession.getLogon().duplicate();
        heartbeatInMSOut = logon.get(LogonType.heartBtInt, DELAY_BETWEEN_CONNECT_AND_LOGON) * 1000L;
        scheduleOut = scheduledExecutorService.schedule(this::manageOutHeartBeat, heartbeatInMSOut, TimeUnit.MILLISECONDS);

        logon.set(LogonType.nextExpectedMsgSeqNum, clientSeqMsgId.current() + 1);
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
        if (option.resetSeqNumToOneOnGap()) {
            writer.write(userSession.getHeader().duplicate(),
                    SequenceResetType.create(false, 1),
                    null, true); // send GapFill
            return;
        }
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
                        writer.write(userSession.getHeader().duplicate(),
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
                writer.write(userSession.getHeader().duplicate(),
                        SequenceResetType.create(true, endSeq == 0 ? clientSeqMsgId.current() + 1 : endSeq + 1),
                        null, false); // send GapFill for trailing admin messages
            }
        } else {
            final int newSeqNo = endSeq;
            log.info(ident + " [resend] reset to end " + (newSeqNo + 1));
            writer.write(userSession.getHeader().duplicate(),
                    SequenceResetType.create(true, newSeqNo + 1), null, false);
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
        long when = System.currentTimeMillis() - (lastMessageReceivedTimeStampInMS + heartbeatInMSIn) + (heartbeatInMSIn * 15) / 100;
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
            if (System.currentTimeMillis() - (lastMessageReceivedTimeStampInMS + heartbeatInMSIn) > heartbeatInMSIn) {
                log.error(ident + ": Heartbeat not received " + expectedHeartbeat + ". Shutdown connection.");
                shutdown.close();
            } else {
                log.info(ident + ": A message was received, continue.");
            }
        }
    }

    private void manageOutHeartBeat() {
        if (userSession == null) {
            return;
        }
        long when = System.currentTimeMillis() - (lastWriteOut + heartbeatInMSOut) - 100;
        if (when > 500) {
            writer.write(userSession.getHeader().duplicate(), HeartbeatType.create(), null, false);
            when = heartbeatInMSOut;
        }
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
