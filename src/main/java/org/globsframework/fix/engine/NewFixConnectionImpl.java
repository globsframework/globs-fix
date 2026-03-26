package org.globsframework.fix.engine;

import org.globsframework.fix.deserializer.FixReader;
import org.globsframework.fix.serializer.FixWriter;

import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;

public class NewFixConnectionImpl implements FixConnectionFactory.NewFixConnection {
    public final ExecutorService executorService;
    public final ScheduledExecutorService scheduledExecutorService;

    public NewFixConnectionImpl(ExecutorService executorService, ScheduledExecutorService scheduledExecutorService) {
        this.executorService = executorService;
        this.scheduledExecutorService = scheduledExecutorService;
    }

    @Override
    public void onNew(FixReader reader, FixWriter writer, Socket socket) {
        executorService.execute(new FixSessionImpl(reader, writer));
    }
}
