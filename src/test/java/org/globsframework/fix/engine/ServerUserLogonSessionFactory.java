package org.globsframework.fix.engine;

import java.util.concurrent.ScheduledExecutorService;

public class ServerUserLogonSessionFactory implements UserLogonSessionFactory {
    private final ScheduledExecutorService scheduledExecutorService;
    private final int maxElementToSend;
    private final long delay;

    public ServerUserLogonSessionFactory(ScheduledExecutorService scheduledExecutorService, int maxElementToSend, long delay) {
        this.scheduledExecutorService = scheduledExecutorService;
        this.maxElementToSend = maxElementToSend;
        this.delay = delay;
    }

    @Override
    public UserLogonSession create(Shutdown shutdown) {
        return new ServerUserLogonSession(shutdown,
                new FixServerTest.PricerImpl(scheduledExecutorService, maxElementToSend, delay));
    }
}
