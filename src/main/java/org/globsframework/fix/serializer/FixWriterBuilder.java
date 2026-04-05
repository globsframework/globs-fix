package org.globsframework.fix.serializer;

public interface FixWriterBuilder {
    FixWriter createWriter(Publish publish, MsgSeqProvider msgSeqProvider);
}
