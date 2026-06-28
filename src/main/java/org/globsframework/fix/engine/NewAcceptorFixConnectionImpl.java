package org.globsframework.fix.engine;

import org.globsframework.fix.Utils;
import org.globsframework.fix.deserializer.ByteReader;
import org.globsframework.fix.serializer.Publish;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;

public class NewAcceptorFixConnectionImpl implements NewFixConnection {
    private static final Logger log = LoggerFactory.getLogger(NewAcceptorFixConnectionImpl.class);
    private final ExecutorService executorService;
    private final int sendCompID;
    private final int targetCompID;
    private final byte sep;
    private FixEngineInit fixEngineInit;

    public NewAcceptorFixConnectionImpl(ExecutorService executorService, ScheduledExecutorService scheduledExecutorService,
                                        int sendCompID, int targetCompID, byte sep,
                                        SerializerProvider serializerProvider, FixInfoProvider fixInfoProvider,
                                        UserLogonSessionFactory serverUserLogonSessionFactory) {
        this.executorService = executorService;
        this.sendCompID = sendCompID;
        this.targetCompID = targetCompID;
        this.sep = sep;
        fixEngineInit = new FixEngineInit(executorService, scheduledExecutorService,
                fixInfoProvider, serializerProvider, serverUserLogonSessionFactory);
    }

    @Override
    public CompletableFuture<FixLogout> onNew(ByteReader byteReader, Publish publish, Shutdown shutdown) {
        log.info("New session to accept.");
        final CompletableFuture<FixLogout> logoutCompletableFuture = new CompletableFuture<>();

        // first message, we look for the targetComp and senderComp to identify the session
        // and it's dictionary.
        executorService.execute(() -> {
            final FixEngineInit.HeaderFixInfo result = getHeaderFixInfo(byteReader);
            fixEngineInit.initFixEngine(byteReader, publish, shutdown, result, logoutCompletableFuture, false);
        });
        return logoutCompletableFuture;
    }


    private FixEngineInit.HeaderFixInfo getHeaderFixInfo(ByteReader byteReader) {
        byte[] buffer = new byte[1024];
        int len = 0;
        String targetCompID = null;
        String senderCompID = null;
        while (targetCompID == null || senderCompID == null) {
            final int read = byteReader.read(buffer, len, buffer.length - len);
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
                            targetCompID = new String(buffer, equalAt + 1, i - equalAt - 1, StandardCharsets.ISO_8859_1);
                        } else if (id == sendCompID) {
                            senderCompID = new String(buffer, equalAt + 1, i - equalAt - 1, StandardCharsets.ISO_8859_1);
                        }
                    } else {
                        throw new RuntimeException("Invalid FIX message format: missing '=' before separator");
                    }
                    equalAt = -1;
                    start = i + 1;
                }
            }
        }
        return new FixEngineInit.HeaderFixInfo(buffer, len, senderCompID, targetCompID);
    }
}
