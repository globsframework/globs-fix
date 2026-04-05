package org.globsframework.fix.deserializer;

import org.globsframework.fix.serializer.MsgSeqProvider;

import java.util.concurrent.atomic.AtomicInteger;

public class BasicMsgSeqProvider implements MsgSeqProvider {
   private final AtomicInteger counter = new AtomicInteger(0);

    @Override
    public int next() {
        return counter.incrementAndGet();
    }

    @Override
    public int curent() {
        return counter.get();
    }
}
