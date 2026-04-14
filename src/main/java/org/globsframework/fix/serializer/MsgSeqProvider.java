package org.globsframework.fix.serializer;

public interface MsgSeqProvider {
    int next();

    void revert();

    void reset();

}
