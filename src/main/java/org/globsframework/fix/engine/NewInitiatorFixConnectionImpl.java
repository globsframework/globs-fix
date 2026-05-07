package org.globsframework.fix.engine;

import org.globsframework.fix.deserializer.ByteReader;
import org.globsframework.fix.serializer.Publish;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;

public class NewInitiatorFixConnectionImpl implements NewFixConnection {
    private final ExecutorService executorService;
    private final ScheduledExecutorService scheduledExecutorService;
    private final UserLogonSessionFactory userLogonSessionFactory;
    private final FixInfoProvider fixInfoProvider;
    private final SerializerProvider serializerProvider;
    private final HeaderDesc headerDesc;
    private final String senderComp;
    private final String targetComp;
    private final FixEngineInit fixEngineInit;

    public NewInitiatorFixConnectionImpl(ExecutorService executorService, ScheduledExecutorService scheduledExecutorService,
                                         UserLogonSessionFactory userLogonSessionFactory,
                                         FixInfoProvider fixInfoProvider,
                                         SerializerProvider serializerProvider,
                                         HeaderDesc headerDesc, String senderComp, String targetComp) {
        this.executorService = executorService;
        this.scheduledExecutorService = scheduledExecutorService;
        this.userLogonSessionFactory = userLogonSessionFactory;
        this.fixInfoProvider = fixInfoProvider;
        this.serializerProvider = serializerProvider;
        this.headerDesc = headerDesc;
        this.senderComp = senderComp;
        this.targetComp = targetComp;
        fixEngineInit = new FixEngineInit(executorService, scheduledExecutorService,
                fixInfoProvider, serializerProvider, userLogonSessionFactory);
    }

    @Override
    public CompletableFuture<FixLogout> onNew(ByteReader byteReader, Publish publish, Shutdown shutdown) {
        final CompletableFuture<FixLogout> logoutCompletableFuture = new CompletableFuture<>();
        fixEngineInit.initFixEngine(byteReader, publish, shutdown,
                new FixEngineInit.HeaderFixInfo(null, 0, senderComp, targetComp), logoutCompletableFuture, true);
        return logoutCompletableFuture;
    }
}
