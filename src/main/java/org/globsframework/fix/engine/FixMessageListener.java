package org.globsframework.fix.engine;

import org.globsframework.fix.deserializer.FixMessageValue;

public interface FixMessageListener {
    void newMessage(FixMessageValue fixMessageValue);
}
