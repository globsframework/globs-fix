package org.globsframework.fix.engine;

import org.globsframework.core.model.Glob;
import org.globsframework.fix.Utils;
import org.globsframework.fix.deserializer.ByteReader;
import org.globsframework.fix.deserializer.FixReader;
import org.globsframework.fix.serializer.FixWriter;
import org.globsframework.fix.serializer.Publish;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;

public class NewInitiatorFixConnectionImpl implements FixConnectionFactory.NewFixConnection {
    private final ExecutorService executorService;
    private final ScheduledExecutorService scheduledExecutorService;
    private final UserLogonSessionFactory userLogonSessionFactory;
    private final FixInfoProvider fixInfoProvider;
    private final SerializerProvider serializerProvider;
    private final HeaderDesc headerDesc;

    public NewInitiatorFixConnectionImpl(ExecutorService executorService, ScheduledExecutorService scheduledExecutorService,
                                         UserLogonSessionFactory userLogonSessionFactory,
                                         FixInfoProvider fixInfoProvider,
                                         SerializerProvider serializerProvider,
                                         HeaderDesc headerDesc) {
        this.executorService = executorService;
        this.scheduledExecutorService = scheduledExecutorService;
        this.userLogonSessionFactory = userLogonSessionFactory;
        this.fixInfoProvider = fixInfoProvider;
        this.serializerProvider = serializerProvider;
        this.headerDesc = headerDesc;
    }

    @Override
    public CompletableFuture<FixConnectionFactory.FixLogout> onNew(ByteReader byteReader, Publish publish, Shutdown shutdown) {

        final CompletableFuture<FixConnectionFactory.FixLogout> logoutCompletableFuture = new CompletableFuture<>();
        final UserLogonSession userLogonSession = userLogonSessionFactory.create(shutdown);
        UserSession userSession = userLogonSession.initiator();
        final Glob header = userSession.getHeader();
        String senderCompID = header.get(headerDesc.senderCompIDField());
        String targetCompID = header.get(headerDesc.targetCompIDField());
        final FixInfoProvider.DataAdapt cachedData = fixInfoProvider.getCachedData(senderCompID, targetCompID);
        final FixWriter writer = cachedData.createWriter(publish, serializerProvider.getWriter(senderCompID, targetCompID));
        final FixReader reader = serializerProvider.getReader(senderCompID, targetCompID).createReader(byteReader);

        final FixSessionImpl fixSession = new FixSessionImpl(scheduledExecutorService, writer,
                userSession,
                cachedData.clientSeqMsgId(),
                cachedData.getSelfMsgSeqProvider(), cachedData.getCachedData(), headerDesc, shutdown, true, FixSessionImpl.Option.def());
        executorService.execute(Utils.loopRead(fixSession, reader));
        logoutCompletableFuture.complete(new FixConnectionFactory.FixLogout() {
            @Override
            public void registerOnClosed(Runnable runnable) {
                fixSession.registerOnClosed(runnable);
            }

            @Override
            public CompletableFuture<Boolean> close() {
                return fixSession.logout();
            }
        });
        return logoutCompletableFuture;
    }
}
