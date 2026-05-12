package org.globsframework.fix.engine;

import org.globsframework.core.metamodel.GlobModel;
import org.globsframework.core.metamodel.impl.DefaultGlobModel;
import org.globsframework.core.model.MutableGlob;
import org.globsframework.fix.HeaderType;
import org.globsframework.fix.TrailerType;
import org.globsframework.fix.UTCFormater;
import org.globsframework.fix.deserializer.DeserializerFixReaderBuilder;
import org.globsframework.fix.deserializer.FixMessageValue;
import org.globsframework.fix.deserializer.FixReader;
import org.globsframework.fix.deserializer.FixReaderBuilder;
import org.globsframework.fix.dictionary.FixModel;
import org.globsframework.fix.dictionary.admin.HeartbeatType;
import org.globsframework.fix.dictionary.admin.LogonType;
import org.globsframework.fix.dictionary.admin.ResendRequestType;
import org.globsframework.fix.dictionary.admin.SequenceResetType;
import org.globsframework.fix.dictionary.xml.FieldFactoryImpl;
import org.globsframework.fix.dictionary.xml.ReadFixDictionary;
import org.globsframework.fix.fix44.app.QuoteRequestType;
import org.globsframework.fix.serializer.Publish;
import org.globsframework.fix.serializer.SerializerFixWriterBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class FixSessionGapTest {
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
        fixWriterBuilder = SerializerFixWriterBuilder.create(fixModel, globModel, HeaderType.TYPE, TrailerType.TYPE,
                UTCFormater.withAutoRefresh(executorService));

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
                false, new FixSessionImpl.Option(-1, -1, 0));
    }

    @Test
    void complexGapScenario() throws Exception {
        // 1. Logon
        fixSession.newMessage(new FixMessageValue(getHeader(1), LogonType.create(10000), null));
        fixReader.read(); // Consume Logon response
        userSession.connected.get(1, TimeUnit.SECONDS);

        // 2. Gap detected: receive seq 5
        fixSession.newMessage(new FixMessageValue(getHeader(5), QuoteRequestType.create("5"), null));
        FixMessageValue resendRequest = fixReader.read();
        assertEquals(ResendRequestType.TYPE, resendRequest.message().getType());
        assertEquals(2, resendRequest.message().get(ResendRequestType.beginSeqNo));
        assertEquals(4, resendRequest.message().get(ResendRequestType.endSeqNo));

        // 3. Receive seq 6 (should be queued in futureAppMessage)
        fixSession.newMessage(new FixMessageValue(getHeader(6), QuoteRequestType.create("6"), null));

        // 4. Fill the gap with SequenceReset (Gap Fill) from 2 to 3
        // seqNum of SequenceReset is 2. newSeqNo is 3.
        fixSession.newMessage(new FixMessageValue(getHeader(2), SequenceResetType.create(true, 3), null));

        // Now expectedNext should be 3.
        // firstReceivedSeqNum was 5.
        // It's still in ConnectedGapSessionState because 3 < 5.

        // 5. Receive seq 3
        fixSession.newMessage(new FixMessageValue(getHeader(3), QuoteRequestType.create("3"), null));

        // 6. Receive seq 4
        fixSession.newMessage(new FixMessageValue(getHeader(4), QuoteRequestType.create("4"), null));

        // Now expectedNext becomes 5. 5 >= 5 (firstReceivedSeqNum).
        // It should process 5 and then 6.

        assertEquals("3", userSession.getMessage().message().get(QuoteRequestType.quoteReqID));
        assertEquals("4", userSession.getMessage().message().get(QuoteRequestType.quoteReqID));
        assertEquals("5", userSession.getMessage().message().get(QuoteRequestType.quoteReqID));
        assertEquals("6", userSession.getMessage().message().get(QuoteRequestType.quoteReqID));
    }

    private MutableGlob getHeader(int seqNum) {
        return HeaderType.create("AF", "BNP")
                .set(HeaderType.msgSeqNum, seqNum);
    }
}
