package org.globsframework.fix.engine;

import org.globsframework.fix.Utils;
import org.globsframework.fix.deserializer.ByteReader;
import org.globsframework.fix.deserializer.FixReader;
import org.globsframework.fix.serializer.FixWriter;
import org.globsframework.fix.serializer.Publish;

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
    private final PerTargetBuilder perTargetBuilder;

    public record PerTarget(CachedData cachedData, FixReader fixReader, FixWriter fixWriter,
                     HeaderDesc headerDesc, UserLogonSessionFactory userLogonSessionFactory) {
    }

    public interface PerTargetBuilder {
        PerTarget create(String senderCompID, String targetCompID, Publish publish, ByteReader byteReader,
                         byte[] initialBuffer, int total);
    }

    public NewAcceptorFixConnectionImpl(ExecutorService executorService, ScheduledExecutorService scheduledExecutorService,
                                        int sendCompID, int targetCompID, byte sep, PerTargetBuilder perTargetBuilder) {
        this.executorService = executorService;
        this.scheduledExecutorService = scheduledExecutorService;
        this.sendCompID = sendCompID;
        this.targetCompID = targetCompID;
        this.sep = sep;
        this.perTargetBuilder = perTargetBuilder;
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
            String targetComp = null;
            String senderComp = null;
            while (targetComp == null && senderComp == null) {
                final int read = byteReader.read(buffer, offset, buffer.length - offset);
                if (read < 1) {
                    throw new RuntimeException("EOF");
                }
                len += read;

                int start = 0;
                int equalAt = -1;
                for (int i = 0; i < len && (targetComp == null || senderComp == null); i++) {
                    if (equalAt == -1 && buffer[i] == '=') {
                        equalAt = i;
                    }
                    if (buffer[i] == sep) {
                        if (equalAt != -1) {
                            final int id = Utils.getIntAt(start, equalAt, buffer);
                            if (id == targetCompID) {
                                targetComp = new String(buffer, equalAt + 1, i - equalAt - 1, StandardCharsets.US_ASCII);
                            } else if (id == sendCompID) {
                                senderComp = new String(buffer, equalAt + 1, i - equalAt - 1, StandardCharsets.US_ASCII);
                            }
                        } else {
                            throw new RuntimeException("Invalid FIX message format: missing '=' before separator");
                        }
                        equalAt = -1;
                        start = i + 1;
                    }
                }
            }

            final PerTarget perTarget = perTargetBuilder.create(senderComp, targetComp, publish, byteReader, buffer, len);
            final FixSessionImpl fixSession = new FixSessionImpl(scheduledExecutorService, perTarget.fixReader(), perTarget.fixWriter(),
                    perTarget.userLogonSessionFactory().create(perTarget.fixWriter(), shutdown),
                    perTarget.cachedData(), perTarget.headerDesc(), shutdown, false);
            logoutCompletableFuture.complete(fixSession::logout);
            executorService.execute(fixSession);
        });
        return logoutCompletableFuture;
    }
}
