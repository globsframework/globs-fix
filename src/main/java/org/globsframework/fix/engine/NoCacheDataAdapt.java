package org.globsframework.fix.engine;

import org.globsframework.fix.deserializer.BasicMsgSeqProvider;
import org.globsframework.fix.serializer.FixWriter;
import org.globsframework.fix.serializer.FixWriterBuilder;
import org.globsframework.fix.serializer.MsgSeqProvider;
import org.globsframework.fix.serializer.Publish;

public class NoCacheDataAdapt implements FixInfoProvider.DataAdapt {
    private final MsgSeqProvider msgSeqProvider = new BasicMsgSeqProvider();
    private final ClientSeqMsgId inMemoryClientSeqMsgId = new InMemoryClientSeqMsgId();

    @Override
    public FixWriter createWriter(Publish publish, FixWriterBuilder writerBuilder) {
        return writerBuilder.createWriter(publish, msgSeqProvider);
    }

    public MsgSeqProvider getSelfMsgSeqProvider() {
        return msgSeqProvider;
    }

    @Override
    public FixMessageRepository getCachedData() {
        return NoFixMessageRepository.INSTANCE;
    }

    @Override
    public ClientSeqMsgId clientSeqMsgId() {
        return inMemoryClientSeqMsgId;
    }
}
