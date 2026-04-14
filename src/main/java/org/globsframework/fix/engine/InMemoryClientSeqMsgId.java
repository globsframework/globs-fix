package org.globsframework.fix.engine;

import java.util.concurrent.atomic.AtomicInteger;

public class InMemoryClientSeqMsgId implements ClientSeqMsgId {
    private final AtomicInteger currentSeqNum = new AtomicInteger(0);

    @Override
    public int next(int expectedNext) {
        final int i = currentSeqNum.incrementAndGet();
        if (i != expectedNext) {
            throw new RuntimeException("invalide state " + i + " was expected but got " + expectedNext);
        }
        return i + 1;
    }

    @Override
    public int current() {
        return currentSeqNum.get();
    }

    @Override
    public int reset(int lastReceived) {
        currentSeqNum.set(lastReceived);
        return lastReceived + 1;
    }
}
