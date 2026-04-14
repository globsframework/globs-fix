package org.globsframework.fix.engine;

public interface ClientSeqMsgId {
    int next(int expectedNext);

    int current();

    int reset(int lastReceived);
}
