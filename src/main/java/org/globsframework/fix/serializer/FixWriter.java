package org.globsframework.fix.serializer;

import org.globsframework.core.model.MutableGlob;
import org.globsframework.fix.engine.FixMessage;
import org.globsframework.fix.engine.FixMessageImpl;

public interface FixWriter {
    default void write(MutableGlob header, MutableGlob message, MutableGlob trailer, boolean resetSeqNum) {
        write(FixMessageImpl.fromGlob(header, message, trailer, resetSeqNum));
    }

    void write(FixMessage message);
}
