package org.globsframework.fix.engine;

import org.globsframework.core.model.Glob;
import org.globsframework.core.model.MutableGlob;

public interface FixMessageRepository {

    FixRecoveredMessage[] get(int fromSeqNum, int toSeqNum);

    record FixRecoveredMessage(MutableGlob header, Glob message, MutableGlob trailer) {
    }
}
