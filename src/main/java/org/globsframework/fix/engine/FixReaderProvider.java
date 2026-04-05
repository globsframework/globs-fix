package org.globsframework.fix.engine;

import org.globsframework.fix.deserializer.FixReaderBuilder;

public interface FixReaderProvider {
    FixReaderBuilder getBuilder(String senderCompID, String targetCompID);
}
