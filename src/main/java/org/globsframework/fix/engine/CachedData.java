package org.globsframework.fix.engine;

import org.globsframework.core.model.Glob;
import org.globsframework.core.model.MutableGlob;

public interface CachedData {

    Data[] get(int fromSeqNum, int toSeqNum);

    record Data(MutableGlob header, Glob message, MutableGlob trailer) {
    }
}
