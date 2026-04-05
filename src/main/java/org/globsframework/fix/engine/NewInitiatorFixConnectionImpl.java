package org.globsframework.fix.engine;

import org.globsframework.fix.deserializer.ByteReader;
import org.globsframework.fix.deserializer.FixReader;
import org.globsframework.fix.deserializer.FixReaderBuilder;
import org.globsframework.fix.serializer.FixWriter;
import org.globsframework.fix.serializer.FixWriterBuilder;
import org.globsframework.fix.serializer.Publish;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;

public class NewInitiatorFixConnectionImpl implements FixConnectionFactory.NewFixConnection {
    private final ExecutorService executorService;
    private final ScheduledExecutorService scheduledExecutorService;
    private final UserLogonSessionFactory userLogonSessionFactory;
    private final CacheProvider cacheProvider;
    private final FixReaderBuilder fixReader;
    private final FixWriterBuilder fixWriter;
    private final HeaderDesc headerDesc;

    public NewInitiatorFixConnectionImpl(ExecutorService executorService, ScheduledExecutorService scheduledExecutorService,
                                         UserLogonSessionFactory userLogonSessionFactory,
                                         CacheProvider cacheProvider,
                                         FixReaderBuilder fixReader,
                                         FixWriterBuilder fixWriter,
                                         HeaderDesc headerDesc) {
        this.executorService = executorService;
        this.scheduledExecutorService = scheduledExecutorService;
        this.userLogonSessionFactory = userLogonSessionFactory;
        this.cacheProvider = cacheProvider;
        this.fixReader = fixReader;
        this.fixWriter = fixWriter;
        this.headerDesc = headerDesc;
    }

    @Override
    public CompletableFuture<FixConnectionFactory.FixLogout> onNew(ByteReader byteReader, Publish publish, Shutdown shutdown) {
        final CacheProvider.SeqNumAndCache cachedData = cacheProvider.getCachedData();
        final FixWriter writer = fixWriter.createWriter(publish, cachedData.msgSeqProvider());
        final FixReader reader = fixReader.createReader(byteReader);

        final CompletableFuture<FixConnectionFactory.FixLogout> logoutCompletableFuture = new CompletableFuture<>();
        final FixSessionImpl fixSession = new FixSessionImpl(scheduledExecutorService, reader, writer,
                userLogonSessionFactory.create(writer, shutdown),
                cachedData.cachedData(), headerDesc, shutdown, true);
        executorService.execute(fixSession);
        logoutCompletableFuture.complete(fixSession::logout);
        return logoutCompletableFuture;
    }
}
