package org.globsframework.fix.engine;

import org.globsframework.fix.Utils;
import org.globsframework.fix.deserializer.ByteReader;
import org.globsframework.fix.deserializer.DeserializerFixReaderBuilder;
import org.globsframework.fix.deserializer.FixReader;
import org.globsframework.fix.serializer.FixWriter;
import org.globsframework.fix.serializer.Publish;
import org.globsframework.fix.serializer.SerializerFixWriterBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;

class FixEngineInit {
    private static final Logger log = LoggerFactory.getLogger(FixEngineInit.class);
    private final ExecutorService executorService;
    private final ScheduledExecutorService scheduledExecutorService;
    private final FixInfoProvider fixInfoProvider;
    private final SerializerProvider serializerProvider;
    private final UserLogonSessionFactory serverUserLogonSessionFactory;

    public FixEngineInit(ExecutorService executorService, ScheduledExecutorService scheduledExecutorService, FixInfoProvider fixInfoProvider, SerializerProvider serializerProvider, UserLogonSessionFactory serverUserLogonSessionFactory) {
        this.executorService = executorService;
        this.scheduledExecutorService = scheduledExecutorService;
        this.fixInfoProvider = fixInfoProvider;
        this.serializerProvider = serializerProvider;
        this.serverUserLogonSessionFactory = serverUserLogonSessionFactory;
    }

    public void initFixEngine(ByteReader byteReader, Publish publish, Shutdown shutdown, HeaderFixInfo result,
                              CompletableFuture<FixLogout> logoutCompletableFuture, boolean isInitiator) {
        final FixInfoProvider.DataAdapt dataAdapt = fixInfoProvider.getCachedData(result.senderCompID(), result.targetCompID());
        final DeserializerFixReaderBuilder readerBuilder = serializerProvider.getReader(result.senderCompID(), result.targetCompID());
        final HeaderDesc headerDesc = serializerProvider.getHeaderDesc(result.senderCompID(), result.targetCompID());
        final FixReader reader = readerBuilder.createReader(byteReader, result.buffer(), result.len());
        final SerializerFixWriterBuilder writerBuilder = serializerProvider.getWriter(result.senderCompID(), result.targetCompID());

        FixWriter writer = dataAdapt.createWriter(publish, writerBuilder);
        final UserSession userLogonSession = serverUserLogonSessionFactory.create(result.senderCompID(),
                result.targetCompID(), shutdown);
        final FixSessionImpl fixSession = new FixSessionImpl(scheduledExecutorService, writer,
                userLogonSession,
                dataAdapt.clientSeqMsgId(),
                dataAdapt.getSelfMsgSeqProvider(),
                dataAdapt.getCachedData(), headerDesc, shutdown, isInitiator,
                FixSessionImpl.Option.def());
        logoutCompletableFuture.complete(new FixLogout() {
            @Override
            public void registerOnClosed(Runnable runnable) {
                fixSession.registerOnClosed(runnable);
            }

            @Override
            public CompletableFuture<Boolean> close() {
                return fixSession.logout();
            }
        });
        executorService.execute(Utils.loopRead(fixSession, reader));
    }

    public record HeaderFixInfo(byte[] buffer, int len, String senderCompID, String targetCompID) {
    }
}
