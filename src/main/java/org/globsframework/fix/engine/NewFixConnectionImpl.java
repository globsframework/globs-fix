package org.globsframework.fix.engine;

import org.globsframework.fix.deserializer.FixReader;
import org.globsframework.fix.serializer.FixWriter;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;

public class NewFixConnectionImpl implements FixConnectionFactory.NewFixConnection {
    public final ExecutorService executorService;
    public final ScheduledExecutorService scheduledExecutorService;
    private final UserLogonSessionFactory userLogonSessionFactory;

    public NewFixConnectionImpl(ExecutorService executorService, ScheduledExecutorService scheduledExecutorService,
                                UserLogonSessionFactory userLogonSessionFactory) {
        this.executorService = executorService;
        this.scheduledExecutorService = scheduledExecutorService;
        this.userLogonSessionFactory = userLogonSessionFactory;
    }

    @Override
    public void onNew(FixReader reader, FixWriter writer, Shutdown shutdown) {
        executorService.execute(new FixSessionImpl(scheduledExecutorService, reader, writer,
                userLogonSessionFactory.create(writer, shutdown), shutdown));
    }
}
