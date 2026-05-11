package org.globsframework.fix.engine;

import org.globsframework.core.model.Glob;
import org.globsframework.core.model.MutableGlob;

public interface FixMessageRepository {

    FixMessage[] get(int fromSeqNum, int toSeqNum);
}
