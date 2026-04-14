package org.globsframework.fix.engine;

import org.globsframework.fix.Utils;
import org.globsframework.fix.deserializer.BasicMsgSeqProvider;
import org.globsframework.fix.deserializer.ByteReader;
import org.globsframework.fix.deserializer.DeserializerFixReaderBuilder;
import org.globsframework.fix.deserializer.FixReader;
import org.globsframework.fix.serializer.*;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;

public class NewAcceptorFixConnectionImpl implements FixConnectionFactory.NewFixConnection {
    public final ExecutorService executorService;
    public final ScheduledExecutorService scheduledExecutorService;
    private final int sendCompID;
    private final int targetCompID;
    private final byte sep;
    private final SerializerProvider serializerProvider;
    private final CacheProvider cacheProvider;
    private final UserLogonSessionFactory serverUserLogonSessionFactory;


    public NewAcceptorFixConnectionImpl(ExecutorService executorService, ScheduledExecutorService scheduledExecutorService,
                                        int sendCompID, int targetCompID, byte sep,
                                        SerializerProvider serializerProvider, CacheProvider cacheProvider,
                                        UserLogonSessionFactory serverUserLogonSessionFactory) {
        this.executorService = executorService;
        this.scheduledExecutorService = scheduledExecutorService;
        this.sendCompID = sendCompID;
        this.targetCompID = targetCompID;
        this.sep = sep;
        this.serializerProvider = serializerProvider;
        this.cacheProvider = cacheProvider;
        this.serverUserLogonSessionFactory = serverUserLogonSessionFactory;
    }

    @Override
    public CompletableFuture<FixConnectionFactory.FixLogout> onNew(ByteReader byteReader, Publish publish, Shutdown shutdown) {

        final CompletableFuture<FixConnectionFactory.FixLogout> logoutCompletableFuture = new CompletableFuture<>();

        // first message, we look for the targetComp and senderComp to identify the session
        // and it's dictionary.
        executorService.execute(() -> {
            byte[] buffer = new byte[1024];
            int offset = 0;
            int len = 0;
            String targetCompID = null;
            String senderCompID = null;
            while (targetCompID == null && senderCompID == null) {
                final int read = byteReader.read(buffer, offset, buffer.length - offset);
                if (read < 1) {
                    throw new RuntimeException("EOF");
                }
                len += read;

                int start = 0;
                int equalAt = -1;
                for (int i = 0; i < len && (targetCompID == null || senderCompID == null); i++) {
                    if (equalAt == -1 && buffer[i] == '=') {
                        equalAt = i;
                    }
                    if (buffer[i] == sep) {
                        if (equalAt != -1) {
                            final int id = Utils.getIntAt(start, equalAt, buffer);
                            if (id == this.targetCompID) {
                                targetCompID = new String(buffer, equalAt + 1, i - equalAt - 1, StandardCharsets.US_ASCII);
                            } else if (id == sendCompID) {
                                senderCompID = new String(buffer, equalAt + 1, i - equalAt - 1, StandardCharsets.US_ASCII);
                            }
                        } else {
                            throw new RuntimeException("Invalid FIX message format: missing '=' before separator");
                        }
                        equalAt = -1;
                        start = i + 1;
                    }
                }
            }

            final CacheProvider.DataAdapt dataAdapt = cacheProvider.getCachedData(senderCompID, targetCompID);
            final DeserializerFixReaderBuilder readerBuilder = serializerProvider.getReader(senderCompID, targetCompID);
            final HeaderDesc headerDesc = serializerProvider.getHeaderDesc(senderCompID, targetCompID);
            final FixReader reader = readerBuilder.createReader(byteReader, buffer, len);
            final SerializerFixWriterBuilder writerBuilder = serializerProvider.getWriter(senderCompID, targetCompID);

            FixWriter writer = dataAdapt.createWriter(publish, writerBuilder);
            final UserLogonSession userLogonSession = serverUserLogonSessionFactory.create(shutdown);
            final FixSessionImpl fixSession = new FixSessionImpl(scheduledExecutorService, reader, writer,
                    userLogonSession.acceptor(senderCompID, targetCompID),
                    dataAdapt.clientSeqMsgId(),
                    dataAdapt.getCachedData(), headerDesc, shutdown, false);
            logoutCompletableFuture.complete(fixSession::logout);
            executorService.execute(fixSession);
        });
        return logoutCompletableFuture;
    }


    static class NoCacheDataAdapt implements CacheProvider.DataAdapt {
        private final MsgSeqProvider msgSeqProvider = new BasicMsgSeqProvider();
        private final ClientSeqMsgId inMemoryClientSeqMsgId = new InMemoryClientSeqMsgId();

        @Override
        public FixWriter createWriter(Publish publish, FixWriterBuilder writerBuilder) {
            return writerBuilder.createWriter(publish, msgSeqProvider);
        }

        public MsgSeqProvider getMsgSeqProvider() {
            return msgSeqProvider;
        }

        @Override
        public CachedData getCachedData() {
            return NoCachedData.INSTANCE;
        }

        @Override
        public ClientSeqMsgId clientSeqMsgId() {
            return inMemoryClientSeqMsgId;
        }
    }
}
