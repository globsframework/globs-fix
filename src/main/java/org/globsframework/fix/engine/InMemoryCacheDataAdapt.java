package org.globsframework.fix.engine;

import org.globsframework.core.metamodel.fields.IntegerField;
import org.globsframework.core.model.Glob;
import org.globsframework.core.model.MutableGlob;
import org.globsframework.fix.deserializer.BasicMsgSeqProvider;
import org.globsframework.fix.dictionary.FixMessage;
import org.globsframework.fix.serializer.FixWriter;
import org.globsframework.fix.serializer.FixWriterBuilder;
import org.globsframework.fix.serializer.MsgSeqProvider;
import org.globsframework.fix.serializer.Publish;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryCacheDataAdapt implements FixInfoProvider.DataAdapt, FixMessageRepository {
    private static final Logger log = LoggerFactory.getLogger(InMemoryCacheDataAdapt.class);
    private final MsgSeqProvider msgSeqProvider = new BasicMsgSeqProvider();
    private final ClientSeqMsgId inMemoryClientSeqMsgId = new InMemoryClientSeqMsgId();
    private final Map<Integer, FixMessageRepository.FixRecoveredMessage> cache = new ConcurrentHashMap<>();
    private final int maxSize;
    private final IntegerField seqNumField;
    private int firstId;

    public InMemoryCacheDataAdapt(int maxSize, IntegerField seqNumField) {
        this.maxSize = maxSize;
        this.seqNumField = seqNumField;
        firstId = 0;
    }

    @Override
    public FixWriter createWriter(Publish publish, FixWriterBuilder writerBuilder) {
        final FixWriter writer = writerBuilder.createWriter(publish, msgSeqProvider);
        return new FixWriter() {
            private boolean full;

            @Override
            synchronized public void write(MutableGlob header, Glob message, MutableGlob trailer, boolean resetSeqNum) {
                writer.write(header, message, trailer, resetSeqNum);
                if (resetSeqNum) {
                    cache.clear();
                    full = false;
                    firstId = -1;
                } else {
                    final int seq = header.get(seqNumField);
                    cache.put(seq, new FixMessageRepository.FixRecoveredMessage(header, message, trailer));
                    if (full) {
                        if (cache.remove(firstId) == null) {
                            log.warn("missing id " + firstId + " in data");
                        }
                        firstId++;
                    } else {
                        full = cache.size() == maxSize;
                        if (firstId == -1) {
                            firstId = seq;
                        }
                    }
                }
            }
        };
    }

    public MsgSeqProvider getMsgSeqProvider() {
        return msgSeqProvider;
    }

    @Override
    public FixMessageRepository getCachedData() {
        return this;
    }

    @Override
    public ClientSeqMsgId clientSeqMsgId() {
        return inMemoryClientSeqMsgId;
    }

    @Override
    public FixRecoveredMessage[] get(int fromSeqNum, int toSeqNum) {
        if (fromSeqNum >= firstId) {
            FixRecoveredMessage[] data = new FixRecoveredMessage[toSeqNum - fromSeqNum + 1];
            for (int i = fromSeqNum; i <= toSeqNum; i++) {
                data[i - fromSeqNum] = cache.get(i);
            }
            return data;
        }
        else {
            return null;
        }
    }
}
