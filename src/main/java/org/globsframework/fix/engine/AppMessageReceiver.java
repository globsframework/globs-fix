package org.globsframework.fix.engine;

import org.globsframework.fix.deserializer.FixMessageValue;

public interface AppMessageReceiver {
    void messages(FixMessageValue fixMessageValue);
}
