package org.globsframework.fix.serializer;

public interface MsgSeqProvider {

    int current();

    int next();

    void revert();

    void reset();

}
