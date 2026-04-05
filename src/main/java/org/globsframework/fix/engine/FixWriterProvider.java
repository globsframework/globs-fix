package org.globsframework.fix.engine;

import org.globsframework.fix.serializer.FixWriterBuilder;

public interface FixWriterProvider {

    FixWriterBuilder getBuilder(String senderCompID, String targetCompId);

}
