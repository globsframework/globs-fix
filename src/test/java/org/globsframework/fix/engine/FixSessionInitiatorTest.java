package org.globsframework.fix.engine;

import org.globsframework.core.metamodel.GlobModel;
import org.globsframework.core.metamodel.impl.DefaultGlobModel;
import org.globsframework.core.model.Glob;
import org.globsframework.core.model.MutableGlob;
import org.globsframework.fix.HeaderType;
import org.globsframework.fix.TrailerType;
import org.globsframework.fix.deserializer.*;
import org.globsframework.fix.dictionary.FixModel;
import org.globsframework.fix.dictionary.admin.*;
import org.globsframework.fix.dictionary.xml.FieldFactoryImpl;
import org.globsframework.fix.dictionary.xml.ReadFixDictionary;
import org.globsframework.fix.fix44.app.QuoteRequestType;
import org.globsframework.fix.serializer.FixWriter;
import org.globsframework.fix.serializer.Publish;
import org.globsframework.fix.serializer.SerializerFixWriterBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FixSessionInitiatorTest {
    private ScheduledExecutorService executorService = Executors.newSingleThreadScheduledExecutor();
    private FixModel fixModel;
    private GlobModel globModel;
    private SerializerFixWriterBuilder fixWriterBuilder;
    private FixReaderBuilder fixReaderBuilder;
    private FixReader fixReader;
    private FixSessionImpl fixSession;
    private TestUserSession userSession;
    private CompletableByteReader completableByteReader;

    @BeforeEach
    void setUp() throws IOException {
        fixModel = ReadFixDictionary.parse("fix44", () ->
                new InputStreamReader(getClass().getClassLoader().getResourceAsStream("FIX44.xml"),
                        StandardCharsets.UTF_8), new FieldFactoryImpl());

        globModel = new DefaultGlobModel(HeartbeatType.TYPE, LogonType.TYPE, QuoteRequestType.TYPE);
        fixWriterBuilder = SerializerFixWriterBuilder.create(fixModel, globModel, HeaderType.TYPE, TrailerType.TYPE);

        fixReaderBuilder = DeserializerFixReaderBuilder.create(fixModel, globModel, HeaderType.TYPE, TrailerType.TYPE);
        completableByteReader = new CompletableByteReader();
        fixReader = fixReaderBuilder.createReader(completableByteReader);

        userSession = new TestUserSession();
        final FixInfoProvider.DataAdapt fixMessageRepository = InMemoryCacheDataAdapt.create(20, HeaderType.msgSeqNum);
        fixSession = new FixSessionImpl(executorService, fixMessageRepository.createWriter(new Publish() {
            @Override
            public void publish(byte[] data, int offset, int length) {
                completableByteReader.getNext()
                        .complete(Arrays.copyOfRange(data, offset, offset + length));
            }
        }, fixWriterBuilder),
                userSession, fixMessageRepository.clientSeqMsgId(),
                fixMessageRepository.getSelfMsgSeqProvider(), fixMessageRepository.getCachedData(),
                HeaderType.getHeaderDesc(), () -> {
        },
                true, new FixSessionImpl.Option(false, -1, -1, 0));
    }

    @Test
    void nominalLogon() throws Exception {
        final FixMessageValue read = fixReader.read();
        assertEquals(LogonType.TYPE, read.message().getType());
        fixSession.newMessage(new FixMessageValue(getNextHeader(1), LogonType.create(10000), null));
        userSession.connected.get(1, TimeUnit.SECONDS);
        fixSession.newMessage(new FixMessageValue(getNextHeader(2), QuoteRequestType.TYPE.instantiate(), null));
        final FixMessageValue message = userSession.getMessage();
        assertEquals(QuoteRequestType.TYPE, message.message().getType());
        fixSession.newMessage(new FixMessageValue(getNextHeader(3), LogoutType.create("Bye"), null));
        final FixMessageValue bye = fixReader.read();
        assertEquals(LogoutType.TYPE, bye.message().getType());
        assertEquals("Requested", bye.message().get(LogoutType.text));
    }

    @Test
    void shutdown() throws Exception {
        final FixMessageValue read = fixReader.read();
        assertEquals(LogonType.TYPE, read.message().getType());
        fixSession.newMessage(new FixMessageValue(getNextHeader(1), LogonType.create(10000), null));
        userSession.connected.get(1, TimeUnit.SECONDS);
        fixSession.logout();
    }

    @Test
    void gapAtStart() throws Exception {
        final FixMessageValue read = fixReader.read();
        assertEquals(LogonType.TYPE, read.message().getType());
        fixSession.newMessage(new FixMessageValue(getNextHeader(10), LogonType.create(10000), null));
        userSession.connected.get(1, TimeUnit.SECONDS);
        final FixMessageValue message = fixReader.read();
        assertEquals(ResendRequestType.TYPE, message.message().getType());
        assertEquals(1, message.message().get(ResendRequestType.beginSeqNo));
        assertEquals(9, message.message().get(ResendRequestType.endSeqNo));
        fixSession.newMessage(new FixMessageValue(getNextHeader(11), QuoteRequestType.create("11"), null));
        assertTrue(userSession.checkEmpty());
        fixSession.newMessage(new FixMessageValue(getNextHeader(1), QuoteRequestType.create("1"), null));
        fixSession.newMessage(new FixMessageValue(getNextHeader(2), QuoteRequestType.create("2"), null));
        fixSession.newMessage(new FixMessageValue(getNextHeader(3), SequenceResetType.create(true, 10), null));
        fixSession.newMessage(new FixMessageValue(getNextHeader(12), QuoteRequestType.create("12"), null));
        assertEquals("1", userSession.getMessage().message().get(QuoteRequestType.quoteReqID));
        assertEquals("2", userSession.getMessage().message().get(QuoteRequestType.quoteReqID));
        assertEquals("11", userSession.getMessage().message().get(QuoteRequestType.quoteReqID));
        assertEquals("12", userSession.getMessage().message().get(QuoteRequestType.quoteReqID));
    }

    @Test
    void testRequestReSend() throws Exception {
        final FixMessageValue read = fixReader.read();
        assertEquals(LogonType.TYPE, read.message().getType());
        fixSession.newMessage(new FixMessageValue(getNextHeader(1), LogonType.create(10000), null));
        userSession.connected.get(1, TimeUnit.SECONDS);

        int[] seNum = new int[5];
        for (int i = 0; i < 5; i++) {
            String content = Integer.toString(i);
            final MutableGlob publish = userSession.publish(QuoteRequestType.create(content));
            seNum[i] = publish.get(HeaderType.msgSeqNum);
            final FixMessageValue msg2 = fixReader.read();
            assertEquals(QuoteRequestType.TYPE, msg2.message().getType());
            assertEquals(content, msg2.message().get(QuoteRequestType.quoteReqID));
        }

        fixSession.newMessage(new FixMessageValue(getNextHeader(2),
                ResendRequestType.create(seNum[2], seNum[3]), null));

        assertEquals("2", fixReader.read().message().get(QuoteRequestType.quoteReqID));
        assertEquals("3", fixReader.read().message().get(QuoteRequestType.quoteReqID));

        userSession.publish(QuoteRequestType.create("6"));
        assertEquals("6", fixReader.read().message().get(QuoteRequestType.quoteReqID));
    }

    private MutableGlob getNextHeader(int seqNum) {
        return HeaderType.create("AF", "BNP")
                .set(HeaderType.msgSeqNum, seqNum);
    }

    private static class TestUserSession implements UserSession, AppMessageReceiver {
        CompletableFuture<Boolean> connected = new CompletableFuture<>();
        List<FixMessageValue> received = new ArrayList<>();
        private FixWriter appWriter;

        @Override
        public void logonFail() {
        }

        @Override
        public Glob getHeader() {
            return HeaderType.create("BNP", "AF");
        }

        @Override
        public Glob getLogon() {
            return LogonType.create(10);
        }

        @Override
        public AppMessageReceiver connected(FixMessageValue logon, FixWriter appWriter) {
            this.appWriter = appWriter;
            connected.complete(true);
            return this;
        }

        @Override
        public CompletableFuture<Void> logout() {
            return new CompletableFuture<>();
        }

        @Override
        public void messages(FixMessageValue fixMessageValue) {
            synchronized (this) {
                received.add(fixMessageValue);
                notify();
            }
        }

        public boolean checkEmpty() throws InterruptedException {
            synchronized (this) {
                if (received.isEmpty()) {
                    this.wait(100);
                }
                return received.isEmpty();
            }
        }

        public FixMessageValue getMessage() throws InterruptedException {
            synchronized (this) {
                if (received.isEmpty()) {
                    this.wait(1000);
                }
                return received.removeFirst();
            }
        }

        public MutableGlob publish(MutableGlob msg) {
            final MutableGlob header = getHeader().duplicate();
            appWriter.write(header, msg, null, false);
            return header;
        }
    }

    private static class CompletableByteReader implements ByteReader {
        byte[] current;
        int readUntil;
        List<CompletableFuture<byte[]>> pending = new ArrayList<>();

        public CompletableFuture<byte[]> getNext() {
            synchronized (this) {
                final CompletableFuture<byte[]> e = new CompletableFuture<>();
                pending.add(e);
                this.notify();
                return e;
            }
        }

        @Override
        public int read(byte[] buf, int offset, int len) {
            if (current == null) {
                try {
                    synchronized (this) {
                        if (pending.isEmpty()) {
                            this.wait(10000);
                        }
                        final CompletableFuture<byte[]> first = pending.removeFirst(); // will throw on timeout
                        current = first.get(10, TimeUnit.SECONDS);
                        readUntil = 0;
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }

            }
            final int available = current.length - readUntil;
            if (len >= available) {
                System.arraycopy(current, readUntil, buf, offset, available);
                current = null;
                readUntil = 0;
                return available;
            } else {
                System.arraycopy(current, readUntil, buf, offset, len);
                readUntil += len;
                return len;
            }
        }
    }
}