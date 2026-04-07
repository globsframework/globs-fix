package org.globsframework.fix.engine;

import org.globsframework.core.metamodel.GlobType;
import org.globsframework.core.model.Glob;
import org.globsframework.core.model.MutableGlob;
import org.globsframework.core.utils.Ref;
import org.globsframework.fix.deserializer.FixMessageValue;
import org.globsframework.fix.deserializer.FixReader;
import org.globsframework.fix.dictionary.admin.*;
import org.globsframework.fix.serializer.FixWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

public class FixSessionImpl implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(FixSessionImpl.class);
    public static final int DELAY_BETWEEN_CONNECT_AND_LOGON = 10;
    private final ScheduledExecutorService scheduledExecutorService;
    private final FixReader reader;
    private final FixWriter writer;
    private final FixWriter appWriter;
    private final CachedData cachedData;
    private final HeaderDesc headerDesc;
    private final boolean isInitiator;
    private final Shutdown shutdown;
    private long heartbeatInMSIn;
    private volatile long lastMessageReceivedTimeStampInMS = -1;
    private long heartbeatInMSOut;
    private volatile long lastWriteOut = -1;
    private String expectedHeartbeat;
    private ClientSeqMsgId lastTreatedSeqNum;
    private ScheduledFuture<?> scheduleOut;
    private ScheduledFuture<?> scheduleIn;
    private UserSession userSession;
    private AppMessageReceiver appMessageReceiver;
    private int expectedNext;
    private volatile boolean closed;

    public FixSessionImpl(ScheduledExecutorService scheduledExecutorService, FixReader fixReader, FixWriter fixWriter,
                          UserSession userSession,
                          CachedData cachedData, // intercept the fixWriter call to update, if needed, the data for replay.
                          HeaderDesc headerDesc,
                          Shutdown shutdown,
                          boolean isInitiator) {
        this.scheduledExecutorService = scheduledExecutorService;
        this.reader = new FixReader() {
            @Override
            public FixMessageValue read() {
                lastMessageReceivedTimeStampInMS = System.currentTimeMillis();
                return fixReader.read();
            }
        };
        this.writer = new FixWriter() {
            @Override
            public void write(MutableGlob header, Glob message, MutableGlob trailer) {
                lastWriteOut = System.currentTimeMillis();
                fixWriter.write(header, message, trailer);
            }
        };
        appWriter = new AppFixWriter(headerDesc);
        this.userSession = userSession;
        this.cachedData = cachedData;
        this.headerDesc = headerDesc;
        this.isInitiator = isInitiator;
        this.shutdown = shutdown;
    }

    public void logout() {
        final CompletableFuture<Void> logout = userSession.logout();
        try {
            if (logout != null) {
                logout.get(1, TimeUnit.SECONDS);
            }
        } catch (Exception _) {
        }
        closed = true;
        writer.write(userSession.getHeader().duplicate(),
                LogoutType.create("Logout requested.")
                , null);

        while (true) {
            // add async call to close in case no response are sent.
            scheduledExecutorService.schedule(() -> {
                shutdown();
            }, 1, TimeUnit.SECONDS);
            final FixMessageValue read = reader.read();
            final Glob message = read.message();
            final GlobType type = message.getType();
            if (type == LogoutType.TYPE) {
                log.info("Client logout confirm");
                shutdown();
                return;
            } else if (type == HeartbeatType.TYPE) {
                final String s = message.get(HeartbeatType.testReqID);
                if (expectedHeartbeat != null) {
                    if (expectedHeartbeat.equals(s)) {
                        expectedHeartbeat = null;
                    } else {
                        log.warn("Unexpected heartbeat: " + s);
                    }
                }
            } else if (type == TestRequestType.TYPE) {
                final String testReqId = message.get(TestRequestType.testReqID);
                writer.write(userSession.getHeader().duplicate(), HeartbeatType.create(testReqId), null);
            } else {
                log.warn("Ignored message type: {}", read);
            }
        }
    }

    public interface UserLogonSession {
        UserSession initiator();

        UserSession acceptor(String senderCompID, String targetCompID);
    }

    public interface UserSession {

        Glob getHeader();

        Glob getLogon();

        ClientSeqMsgId getSeqMsg();

        AppMessageReceiver connected(FixMessageValue logon, FixWriter appWriter);

        CompletableFuture<Void> logout();
    }

    public interface AppMessageReceiver {
        void messages(FixMessageValue fixMessageValue);
    }

    public interface ClientSeqMsgId {
        void next(int expectedNext);

        int current();

        void reset(int lastReceived);
    }

    @Override
    public void run() {
        try {
            if (isInitiator) {
                lastTreatedSeqNum = userSession.getSeqMsg();
                expectedNext = lastTreatedSeqNum.current() + 1;
                sentLogon();
                treatWaitForLogon();
            } else {
                FixMessageValue logon = reader.read();
                if (logon.message().getType() != LogonType.TYPE) {
                    throw new IncoherentStateException("Logon expected but got " + logon.message().getType().getName());
                }

                managedInHeartBeat(logon);

                lastTreatedSeqNum = userSession.getSeqMsg();
                int seq = logon.header().get(headerDesc.seqNumField());
                expectedNext = lastTreatedSeqNum.current() + 1;
                if (expectedNext > seq) { // not expected to happen ignore?
                    throw new IncoherentStateException("Unexpected sequence number: " + seq + " expected: " + expectedNext);
                } else if (expectedNext < seq) {
                    List<FixMessageValue> messages = new ArrayList<>();
                    requestAndManageGap(expectedNext, seq, logon, (message, past) -> {
                        messages.add(message);
                    });
                    sentLogon();
                    appMessageReceiver = userSession.connected(logon, appWriter);
                    for (FixMessageValue message : messages) {
                        treatMsgAndReset(message);
                    }
                } else {
                    sentLogon();
                    // last received is logon.
                    lastTreatedSeqNum.next(expectedNext);
                    appMessageReceiver = userSession.connected(logon, appWriter);
                }
            }
        } catch (RuntimeException e) {
            // loggout
            return;
        }
        loopMessage();
    }

    private void treatWaitForLogon() {
        List<FixMessageValue> messages = new ArrayList<>();
        while (true) {
            FixMessageValue read = reader.read();
            final int seq = read.header().get(headerDesc.seqNumField());
            if (seq > expectedNext) {
                Ref<FixMessageValue> logonRef = new Ref<>();
                List<FixMessageValue> replayMsg = new ArrayList<>();
                requestAndManageGap(expectedNext, seq, read, (e, past) -> {
                    if (!past) {
                        final GlobType type = e.message().getType();
                        if (type == LogonType.TYPE) {
                            logonRef.set(e);
                        } else if (type == LogoutType.TYPE) {
                            throw new IncoherentStateException("receive logout during logon process");
                        }
                    }
                    replayMsg.add(e);
                });
                if (logonRef.get() != null) {
                    managedInHeartBeat(logonRef.get());
                    appMessageReceiver = userSession.connected(logonRef.get(), appWriter);
                    for (FixMessageValue message : messages) {
                        treatMsgAndReset(message);
                    }
                    for (FixMessageValue fixMessageValue : replayMsg) {
                        treatMsgAndReset(fixMessageValue);
                    }
                    return;
                }
            } else if (seq < expectedNext) {
                if (!read.header().isTrue(headerDesc.isDup())) {
                    throw new IncoherentStateException("Sequence number received " + seq + " is lower than expected: " + expectedNext);
                }
            } else {
                if (read.message().getType() == LogonType.TYPE) {
                    managedInHeartBeat(read);
                    appMessageReceiver = userSession.connected(read, appWriter);
                    for (FixMessageValue message : messages) {
                        treatMsgAndReset(message);
                    }
                    return;
                } else {
                    messages.add(read);
                }
            }
        }
    }

    private void treatMsgAndReset(FixMessageValue fixMessageValue) {
        if (FixAdminModel.TYPES.contains(fixMessageValue.message().getType())) {
            manageAdminMessage(fixMessageValue.message().getType(), fixMessageValue);
        } else {
            appMessageReceiver.messages(fixMessageValue);
        }
        lastTreatedSeqNum.reset(fixMessageValue.header().get(headerDesc.seqNumField()));
    }


    private void loopMessage() {
        try {
            while (true) {
                final FixMessageValue read = reader.read();
                treatNextMessage(read);
            }
        } catch (LogoutException logoutException) {
            log.info("End of session: logout " + logoutException.getMessage());
        } catch (GapInRefillException gapInRefillException) {
            log.error("Force logout du to gap in refill detected: " + gapInRefillException.getMessage());
            logout();
        } catch (IncoherentStateException incoherentStateException) {
            log.error("Incoherent state detected: " + incoherentStateException.getMessage());
            logout();
        } catch (Exception exception) {
            log.error("Unexpected error in message loop: " + exception.getMessage(), exception);
            shutdown();
        }
    }

    private void sentLogon() {
        final MutableGlob logon = userSession.getLogon().duplicate();
        heartbeatInMSOut = logon.get(LogonType.heartBtInt, DELAY_BETWEEN_CONNECT_AND_LOGON) * 1000L;
        scheduleOut = scheduledExecutorService.schedule(this::manageOutHeartBeat, heartbeatInMSOut, TimeUnit.MILLISECONDS);

        logon.set(LogonType.nextExpectedMsgSeqNum, lastTreatedSeqNum.current() + 1);
        writer.write(userSession.getHeader().duplicate(), logon, null);
    }

    private void treatNextMessage(FixMessageValue read) {
        final Glob header = read.header();
        int seq = header.getNotNull(headerDesc.seqNumField());
        if (seq != expectedNext) {
            log.warn("Gap detected " + seq + " != " + expectedNext);
            if (seq < expectedNext) {
                if (!header.isTrue(headerDesc.isDup())) {
                    final String msg = "Receive old sequence number " + seq + " ";
                    log.error(msg);
                    throw new RuntimeException(msg);
                }else {
                    log.warn("Duplicate message received, ignoring {} ", read);
                }
            } else {
                requestAndManageGap(expectedNext, seq, read, (fixMessageValue, past) -> {
                    treatMsg(fixMessageValue);
                });
            }
        } else {
            treatMsg(read);
        }
    }

    private void treatMsg(FixMessageValue fixMessageValue) {
        if (FixAdminModel.TYPES.contains(fixMessageValue.message().getType())) {
            manageAdminMessage(fixMessageValue.message().getType(), fixMessageValue);
        } else {
            appMessageReceiver.messages(fixMessageValue);
        }
        if (fixMessageValue.header().get(headerDesc.seqNumField()) != expectedNext) {
            throw new RuntimeException("BUG: Unexpected sequence number, expected " + expectedNext +
                                       " but got " + fixMessageValue.header().get(headerDesc.seqNumField()));
        }
        lastTreatedSeqNum.next(expectedNext);
    }

    interface Publish {
        void publish(FixMessageValue message, boolean past);
    }

    private boolean requestAndManageGap(int firstUnknownSeqNum, int firstKnownReceivedSeqNum,
                                        FixMessageValue lastMessage, Publish publish) {

        writer.write(userSession.getHeader().duplicate(), ResendRequestType.create(firstUnknownSeqNum, firstKnownReceivedSeqNum - 1), null);

        int nextWantedSeqNum = firstUnknownSeqNum;
        expectedNext = firstKnownReceivedSeqNum + 1;
        List<FixMessageValue> nextMessages = new ArrayList<>();
        nextMessages.add(lastMessage);
        FixMessageValue read = reader.read();
        while (true) {
            final Glob header = read.header();
            int seq = header.get(headerDesc.seqNumField());
            final Glob message = read.message();
            if (seq == firstKnownReceivedSeqNum - 1) {
                log.info("End of gap");
                publish.publish(read, true);
                for (FixMessageValue nextMessage : nextMessages) {
                    publish.publish(nextMessage, false);
                }
                return true;
            }
            if (seq == expectedNext) {
                nextMessages.add(read);
                if (message.getType() == SequenceResetType.TYPE) {
                    if (message.isTrue(SequenceResetType.gapFillFlag)) {
                        final int tmp = message.get(SequenceResetType.newSeqNo);
                        nextWantedSeqNum = Math.max(tmp, nextWantedSeqNum);
                        if (nextWantedSeqNum >= firstKnownReceivedSeqNum) {
                            nextMessages.add(read);
                            for (FixMessageValue nextMessage : nextMessages) {
                                publish.publish(nextMessage, false);
                            }
                            return true;
                        }
                    } else {
                        final int tmp = message.get(SequenceResetType.newSeqNo);
                        lastTreatedSeqNum.reset(tmp);
                        expectedNext = tmp;
                        for (FixMessageValue nextMessage : nextMessages) {
                            publish.publish(nextMessage, false);
                        }
                        // check what to do with pending messages.
                        return false;
                    }
                }
                expectedNext++;
            } else if (seq > expectedNext) {
                final String msg = "Gap during refill, expecting " + expectedNext + " but got " + seq;
                log.error(msg);
                throw new GapInRefillException(msg);
            } else {
                if (nextWantedSeqNum != seq) {
                    final String msg = "During refill, expecting " + nextWantedSeqNum + " but got " + seq;
                    log.error(msg);
                    throw new GapInRefillException(msg);
                }
                if (!read.header().isTrue(headerDesc.isDup())) {
                    final String msg = "During refill, got " + seq + " but not dup.";
                    log.error(msg);
                    throw new GapInRefillException(msg);
                }
                publish.publish(read, true);
                nextWantedSeqNum++;
            }
            read = reader.read();
        }
    }

    private void manageAdminMessage(GlobType type, FixMessageValue message) {
        if (type == LogonType.TYPE) {
            // ignored : can happen if a ResendQuest was sent immediately after a logon
        } else {
            if (type == LogoutType.TYPE) {
                final CompletableFuture<Void> logout = userSession.logout();
                if (logout != null) {
                    try {
                        logout.get(1, TimeUnit.SECONDS);
                    } catch (Exception _) {
                    }
                }
                closed = true;
                writer.write(userSession.getHeader().duplicate(), LogoutType.create("received logout"), null);
                shutdown();
                throw new LogoutException("Session ended");
            } else if (type == HeartbeatType.TYPE) {
                final String s = message.message().get(HeartbeatType.testReqID);
                if (expectedHeartbeat != null) {
                    if (expectedHeartbeat.equals(s)) {
                        expectedHeartbeat = null;
                    } else {
                        log.warn("Unexpected heartbeat: " + s);
                    }
                }
            } else if (type == TestRequestType.TYPE) {
                final String testReqId = message.message().get(TestRequestType.testReqID);
                writer.write(userSession.getHeader().duplicate(), HeartbeatType.create(testReqId), null);
            } else if (type == ResendRequestType.TYPE) {
                // send async
                treatReSend(message);
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
        final CachedData.Data[] data = cachedData.get(beginSeq, endSeq);
        if (data != null && data.length > 0) {
            int gapfill = -1;
            for (CachedData.Data d : data) {
                if (FixAdminModel.TYPES.contains(d.message().getType())) {
                    if (gapfill == -1) {
                        gapfill = d.header().get(headerDesc.seqNumField());
                    }
                } else {
                    if (gapfill != -1) {
                        writer.write(userSession.getHeader().duplicate(),
                                SequenceResetType.create(true, d.header().get(headerDesc.seqNumField())),
                                null); // send GapFill
                        gapfill = -1;
                    }
                    writer.write(d.header().duplicate()
                                    .set(headerDesc.isDup(), true)
                                    .set(headerDesc.origSendingTime(), d.header().get(headerDesc.sendingTime()))
                            , d.message(), d.trailer());
                }
            }
            if (gapfill != -1) {
                writer.write(userSession.getHeader().duplicate(),
                        SequenceResetType.create(true, endSeq == 0 ? lastTreatedSeqNum.current() + 1 : endSeq + 1),
                        null); // send GapFill for trailing admin messages
            }
        } else {
            int seq = message.header().get(headerDesc.seqNumField(), -1);
            writer.write(userSession.getHeader().duplicate(),
                    SequenceResetType.create(true, endSeq == 0 ? seq : endSeq), null);
        }
    }

    private void managedInHeartBeat(FixMessageValue message) {
        final Glob logon = message.message();
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
            writer.write(userSession.getHeader().duplicate(), TestRequestType.create(expectedHeartbeat), null);
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
            log.info("Requested heartbeat was received and cleared");
            manageInHeartBeat();
        } else {
            if (System.currentTimeMillis() - (lastMessageReceivedTimeStampInMS + heartbeatInMSIn) > heartbeatInMSIn) {
                log.error("Heartbeat not received " + expectedHeartbeat + ". Shutdown connection.");
                shutdown.close();
            }
            else {
                log.info("A message was received, continue.");
            }
        }
    }

    private void manageOutHeartBeat() {
        if (userSession == null) {
            return;
        }
        long when = System.currentTimeMillis() - (lastWriteOut + heartbeatInMSOut) - 100;
        if (when > 500) {
            writer.write(userSession.getHeader().duplicate(), HeartbeatType.create(), null);
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
        public void write(MutableGlob header, Glob message, MutableGlob trailer) {
            if (closed) {
                throw new RuntimeException("Session is closed");
            }
            header.unset(headerDesc.seqNumField());// to prevent any bug
            writer.write(header, message, trailer);
        }
    }

    static class LogoutException extends RuntimeException {
        public LogoutException(String message) {
            super(message);
        }
    }

    static class GapInRefillException extends RuntimeException {

        public GapInRefillException(String msg) {
            super(msg);
        }
    }

    static class IncoherentStateException extends RuntimeException {

        public IncoherentStateException(String message) {
            super(message);
        }
    }

}
