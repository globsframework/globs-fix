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

public class FixConnectionFactory implements OnNewConnection {
    private static final Logger log = LoggerFactory.getLogger(FixConnectionFactory.class);
    private final NewFixConnection newFixConnection;
    private final Publish decorate;

    public FixConnectionFactory(NewFixConnection newFixConnection, Publish decorate) {
        this.newFixConnection = newFixConnection;
        this.decorate = decorate;
    }


    public interface FixLogout {
        CompletableFuture<Boolean> close();
    }

    public interface NewFixConnection {
        CompletableFuture<FixLogout> onNew(ByteReader reader, Publish writer, Shutdown shutdown);
    }

    @Override
    public CompletableFuture<FixConnectionFactory.FixLogout> newConnection(Socket socket) {
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
                log.info("read " + new String(buf, offset, read));
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
