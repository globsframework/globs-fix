package org.globsframework.fix.engine;

import java.util.concurrent.atomic.AtomicInteger;

public class InMemoryClientSeqMsgId implements ClientSeqMsgId {
    private final AtomicInteger currentSeqNum = new AtomicInteger(0);

    @Override
    public int next(int expectedNext) {
        int expected = currentSeqNum.get() + 1;
        if (expected != expectedNext) {
            throw new RuntimeException("invalide state " + expected + " was expected but got " + expectedNext);
        }
        return currentSeqNum.incrementAndGet() + 1;
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
