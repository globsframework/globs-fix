package org.globsframework.fix.deserializer;

import org.globsframework.fix.serializer.MsgSeqProvider;

public class BasicMsgSeqProvider implements MsgSeqProvider {
    private int counter = 0;
    private boolean closed = false;

    @Override
    synchronized public int next() {
        if (closed) {
            throw new RuntimeException("seq num provider is closed");
        }
        return ++counter;
    }

    @Override
    synchronized public void revert() {
        if (closed) {
            throw new RuntimeException("seq num provider is closed can not revert");
        }
        counter--;
    }

    @Override
    synchronized public void reset() {
        if (closed) {
            throw new RuntimeException("seq num provider is closed can not reset");
        }
        counter = 0;
    }
}
