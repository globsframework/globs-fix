package org.globsframework.fix.engine;

import org.globsframework.fix.deserializer.ByteReader;
import org.globsframework.fix.serializer.Publish;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;

public class FixConnectionFactory {
    private static final Logger log = LoggerFactory.getLogger(FixConnectionFactory.class);
    private final Publish decorate;
    private final ExecutorService executorService;
    private final ScheduledExecutorService scheduledExecutorService;
    private final UserLogonSessionFactory userLogonSessionFactory;
    private final FixInfoProvider fixInfoProvider;
    private final SerializerProvider serializerProvider;
    private final HeaderDesc headerDesc;

    public FixConnectionFactory(Publish decorate, ExecutorService executorService, ScheduledExecutorService scheduledExecutorService,
                                UserLogonSessionFactory userLogonSessionFactory,
                                FixInfoProvider fixInfoProvider,
                                SerializerProvider serializerProvider,
                                HeaderDesc headerDesc) {
        this.decorate = decorate;
        this.executorService = executorService;
        this.scheduledExecutorService = scheduledExecutorService;
        this.userLogonSessionFactory = userLogonSessionFactory;
        this.fixInfoProvider = fixInfoProvider;
        this.serializerProvider = serializerProvider;
        this.headerDesc = headerDesc;
    }

    public NewFixConnection createAcceptor() {
        return new NewAcceptorFixConnectionImpl(executorService, scheduledExecutorService, 49, 56, (byte) 0x1,
                serializerProvider, fixInfoProvider, userLogonSessionFactory);
    }

    public NewFixConnection createInitiator(String senderComp, String targetComp) {
        return new NewInitiatorFixConnectionImpl(executorService, scheduledExecutorService, userLogonSessionFactory,
                fixInfoProvider, serializerProvider, headerDesc, senderComp, targetComp);
    }

    public CompletableFuture<FixLogout> newConnection(Socket socket, NewFixConnection newFixConnection) {
        final InputStream inputStream;
        try {
            inputStream = socket.getInputStream();
            final OutputStream outputStream = socket.getOutputStream();
            final ByteReader byteReader = new ByteReaderImpl(inputStream);
            final Publish publish = decorate != null ? new DecoratePublish(new PublishImpl(outputStream), decorate) : new PublishImpl(outputStream);
            return newFixConnection.onNew(byteReader, publish, () -> {
                try {
                    socket.close();
                } catch (IOException e) {
                }
            });
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static class ByteReaderImpl implements ByteReader {
        private final InputStream inputStream;

        public ByteReaderImpl(InputStream inputStream) {
            this.inputStream = inputStream;
        }

        @Override
        public int read(byte[] buf, int offset, int len) {
            try {
                final int read = inputStream.read(buf, offset, len);
                if (read == -1) {
                    log.info("End of stream reached");
                    return -1;
                }
//                log.info("read " + new String(buf, offset, read));
                return read;
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private static class PublishImpl implements Publish {
        private final OutputStream outputStream;

        public PublishImpl(OutputStream outputStream) {
            this.outputStream = outputStream;
        }

        @Override
        public void publish(byte[] data, int offset, int length) {
            try {
                outputStream.write(data, offset, length);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private record DecoratePublish(Publish p1,
                                   Publish p2) implements Publish {

        @Override
        public void publish(byte[] data, int offset, int length) {
            p1.publish(data, offset, length);
            p2.publish(data, offset, length);
        }
    }

}
