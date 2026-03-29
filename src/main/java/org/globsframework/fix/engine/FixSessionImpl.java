package org.globsframework.fix.engine;

import org.globsframework.core.metamodel.GlobType;
import org.globsframework.core.model.Glob;
import org.globsframework.fix.deserializer.FixMessageValue;
import org.globsframework.fix.deserializer.FixReader;
import org.globsframework.fix.dictionary.admin.*;
import org.globsframework.fix.serializer.FixWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class FixSessionImpl implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(FixSessionImpl.class);
    public static final int DELAY_BETWEEN_CONNECT_AND_LOGON = 10;
    private final ScheduledExecutorService scheduledExecutorService;
    private final FixReader reader;
    private final FixWriter writer;
    private final UserLogonSession userLogonSession;
    private final Shutdown shutdown;
    private UserSession session;
    private long heartbeatInMSOut;
    private volatile long lastHeartBeatOut = -1;
    private long heartbeatInMSIn;
    private volatile long nextHeartBeatIn = -1;
    private String expectedHeartbeat;
    private ScheduledFuture<?> schedule;

    public FixSessionImpl(ScheduledExecutorService scheduledExecutorService, FixReader reader, FixWriter writer,
                          UserLogonSession userLogonSession, Shutdown shutdown) {
        this.scheduledExecutorService = scheduledExecutorService;
        this.reader = reader;
        this.writer = new FixWriter() {
            @Override
            public void write(Glob header, Glob message, Glob trailer) {
                lastHeartBeatOut = System.currentTimeMillis(); // before to be conservative
                writer.write(header, message, trailer);
            }
        };
        this.userLogonSession = userLogonSession;
        this.shutdown = shutdown;
    }


    public interface UserLogonSession {
        Glob sendLogon(FixWriter writer); // return the logon message : use to know the wanted heartbeat rate

        UserSession receiveLogon(FixMessageValue fixMessageValue);
    }

    public interface UserSession {
        void messages(FixMessageValue fixMessageValue);

        void logout(FixMessageValue fixMessageValue);
    }

    @Override
    public void run() {
        final Glob loggon = userLogonSession.sendLogon(writer);
        heartbeatInMSIn = loggon.get(LogonType.heartBtInt, DELAY_BETWEEN_CONNECT_AND_LOGON) * 1000L;
        nextHeartBeatIn = System.currentTimeMillis() + heartbeatInMSIn;
        scheduledExecutorService.schedule(this::manageInHeartBeat, heartbeatInMSIn, TimeUnit.MILLISECONDS);

        while (true) {
            final FixMessageValue read = reader.read();
            final Glob message = read.message();
            nextHeartBeatIn = System.currentTimeMillis() + heartbeatInMSIn;
            if (message != null) {
                if (FixAdminModel.TYPES.contains(message.getType())) {
                    manageAdminMessage(message.getType(), read);
                }
            }
            if (session != null) {
                session.messages(read);
            } else {
                log.warn("No session, stop looping for " + read);
                return;
            }
        }
    }

    private void manageAdminMessage(GlobType type, FixMessageValue message) {
        if (type == LogonType.TYPE) {
            managedInLogon(message);
        } else {
            if (session == null) {
                log.warn("No session, cannot manage any other admin message " + type.getName());
                return;
            }
            if (type == LogoutType.TYPE) {
                session.logout(message);
                shutdown.close();
                schedule.cancel(false);
                session = null;
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
                writer.write(null, HeartbeatType.create(testReqId), null);
            }
        }
    }

    private void managedInLogon(FixMessageValue message) {
        session = userLogonSession.receiveLogon(message);
        if (session == null) {
            writer.write(null, LogoutType.create("Refused"), null);
            shutdown.close();
        } else {
            final Glob logon = message.message();
            heartbeatInMSOut = logon.get(LogonType.heartBtInt, DELAY_BETWEEN_CONNECT_AND_LOGON) * 1000L;
            schedule = scheduledExecutorService.schedule(this::manageOutHeartBeat, heartbeatInMSOut - 100, TimeUnit.MILLISECONDS);
        }
    }

    private void manageInHeartBeat() {
        if (session == null) {
            return;
        }
        long when = nextHeartBeatIn - System.currentTimeMillis();
        if (when <= 100) {
            expectedHeartbeat = UUID.randomUUID().toString();
            writer.write(null, TestRequestType.create(expectedHeartbeat), null);
            schedule = scheduledExecutorService.schedule(this::checkReceived, heartbeatInMSIn, TimeUnit.MILLISECONDS);
        } else {
            schedule = scheduledExecutorService.schedule(this::manageInHeartBeat, when, TimeUnit.MILLISECONDS);
        }
    }

    private void checkReceived() {
        if (session == null) {
            return;
        }
        if (expectedHeartbeat == null) {
            log.info("Requested heartbeat received");
            manageInHeartBeat();
        } else {
            log.error("Heartbeat not received " + expectedHeartbeat + ". Shutdown connection.");
            shutdown.close();
        }
    }

    private void manageOutHeartBeat() {
        long when = (lastHeartBeatOut + heartbeatInMSOut) - System.currentTimeMillis();
        if (when <= 500) {
            writer.write(null, HeartbeatType.create(), null);
            when = heartbeatInMSOut;
        }
        scheduledExecutorService.schedule(this::manageOutHeartBeat, when - 100, TimeUnit.MILLISECONDS);
    }
}
