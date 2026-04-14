package org.globsframework.fix.engine;

import org.globsframework.fix.serializer.FixWriter;
import org.globsframework.fix.serializer.FixWriterBuilder;
import org.globsframework.fix.serializer.Publish;

public interface CacheProvider {
    DataAdapt getCachedData(String senderCompID, String targetCompID);

    interface DataAdapt {
        FixWriter createWriter(Publish publish, FixWriterBuilder writerBuilder);

        CachedData getCachedData();

        ClientSeqMsgId clientSeqMsgId();
    }

//    record SeqNumAndCache(CachedData cachedData, MsgSeqProvider msgSeqProvider, FixSessionImpl.ClientSeqMsgId clientSeqMsgId) {
//    }
}
