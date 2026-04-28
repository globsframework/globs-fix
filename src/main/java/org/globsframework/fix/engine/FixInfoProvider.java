package org.globsframework.fix.engine;

import org.globsframework.fix.serializer.FixWriter;
import org.globsframework.fix.serializer.FixWriterBuilder;
import org.globsframework.fix.serializer.MsgSeqProvider;
import org.globsframework.fix.serializer.Publish;

public interface FixInfoProvider {
    DataAdapt getCachedData(String senderCompID, String targetCompID);

    interface DataAdapt {
        FixWriter createWriter(Publish publish, FixWriterBuilder writerBuilder);

        MsgSeqProvider getSelfMsgSeqProvider();

        FixMessageRepository getCachedData();

        ClientSeqMsgId clientSeqMsgId();
    }

//    record SeqNumAndCache(CachedData cachedData, MsgSeqProvider msgSeqProvider, FixSessionImpl.ClientSeqMsgId clientSeqMsgId) {
//    }
}
