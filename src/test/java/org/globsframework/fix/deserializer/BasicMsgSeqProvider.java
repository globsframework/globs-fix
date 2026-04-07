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
    synchronized public int curent() {
        if (closed) {
            throw new RuntimeException("seq num provider is closed");
        }
        return counter;
    }

}
