package org.globsframework.fix.engine;

import org.globsframework.core.model.Glob;
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
    private final SerializerProvider serializerProvider;
    private final HeaderDesc headerDesc;

    public NewInitiatorFixConnectionImpl(ExecutorService executorService, ScheduledExecutorService scheduledExecutorService,
                                         UserLogonSessionFactory userLogonSessionFactory,
                                         CacheProvider cacheProvider,
                                         SerializerProvider serializerProvider,
                                         HeaderDesc headerDesc) {
        this.executorService = executorService;
        this.scheduledExecutorService = scheduledExecutorService;
        this.userLogonSessionFactory = userLogonSessionFactory;
        this.cacheProvider = cacheProvider;
        this.serializerProvider = serializerProvider;
        this.headerDesc = headerDesc;
    }

    @Override
    public CompletableFuture<FixConnectionFactory.FixLogout> onNew(ByteReader byteReader, Publish publish, Shutdown shutdown) {

        final CompletableFuture<FixConnectionFactory.FixLogout> logoutCompletableFuture = new CompletableFuture<>();
        final FixSessionImpl.UserLogonSession userLogonSession = userLogonSessionFactory.create(shutdown);
        FixSessionImpl.UserSession userSession = userLogonSession.initiator();
        final Glob header = userSession.getHeader();
        String senderCompID = header.get(headerDesc.senderCompIDField());
        String targetCompID = header.get(headerDesc.targetCompIDField());
        final CacheProvider.SeqNumAndCache cachedData = cacheProvider.getCachedData(senderCompID, targetCompID);
        final FixWriter writer = serializerProvider.getWriter(senderCompID, targetCompID).createWriter(publish, cachedData.msgSeqProvider());
        final FixReader reader = serializerProvider.getReader(senderCompID, targetCompID).createReader(byteReader);

        final FixSessionImpl fixSession = new FixSessionImpl(scheduledExecutorService, reader, writer,
                userSession,
                cachedData.clientSeqMsgId(),
                cachedData.cachedData(), headerDesc, shutdown, true);
        executorService.execute(fixSession);
        logoutCompletableFuture.complete(fixSession::logout);
        return logoutCompletableFuture;
    }
}
