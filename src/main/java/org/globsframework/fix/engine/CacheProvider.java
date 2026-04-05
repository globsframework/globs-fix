package org.globsframework.fix.engine;

import org.globsframework.fix.serializer.MsgSeqProvider;

public interface CacheProvider {
    SeqNumAndCache getCachedData();

    record SeqNumAndCache(CachedData cachedData, MsgSeqProvider msgSeqProvider) {
    }
}
