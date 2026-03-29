package org.globsframework.fix.engine;

import org.globsframework.fix.deserializer.ByteReader;
import org.globsframework.fix.deserializer.FixReadBuilder;
import org.globsframework.fix.deserializer.FixReader;
import org.globsframework.fix.serializer.FixWriter;
import org.globsframework.fix.serializer.FixWriterBuilder;
import org.globsframework.fix.serializer.FixWriterImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

public class FixConnectionFactory implements OnNewConnection {
    private static final Logger log = LoggerFactory.getLogger(FixConnectionFactory.class);
    private final FixReadBuilder fixReadBuilder;
    private final FixWriterBuilder fixWriterBuilder;
    private final NewFixConnection newFixConnection;

    public FixConnectionFactory(FixReadBuilder fixReadBuilder, FixWriterBuilder fixWriterBuilder,
                                NewFixConnection newFixConnection) {
        this.fixReadBuilder = fixReadBuilder;
        this.fixWriterBuilder = fixWriterBuilder;
        this.newFixConnection = newFixConnection;
    }

    public interface NewFixConnection {
        void onNew(FixReader reader, FixWriter writer, Shutdown shutdown);
    }

    @Override
    public void newConnection(Socket socket) {
        final InputStream inputStream;
        try {
            inputStream = socket.getInputStream();
            final FixReader reader = fixReadBuilder.createReader(new ByteReaderImpl(inputStream));
            final OutputStream outputStream = socket.getOutputStream();
            final FixWriter writer = fixWriterBuilder.createWriter(new PublishImpl(outputStream));
            newFixConnection.onNew(reader, writer, () -> {
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
                log.info("read " + new String(buf, offset, read));
                return read;
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private static class PublishImpl implements FixWriterImpl.Publish {
        private final OutputStream outputStream;

        public PublishImpl(OutputStream outputStream) {
            this.outputStream = outputStream;
        }

        @Override
        public void publish(byte[] data, int offset, int length) {
            try {
                outputStream.write(data, offset, length);
                log.info("publish "  + new String(data, offset, length));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
