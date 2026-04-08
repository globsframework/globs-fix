package org.globsframework.fix.engine;

import org.globsframework.fix.deserializer.DeserializerFixReaderBuilder;
import org.globsframework.fix.serializer.SerializerFixWriterBuilder;

public interface SerializerProvider {
    HeaderDesc getHeaderDesc(String senderCompID, String targetCompID);

    DeserializerFixReaderBuilder getReader(String senderCompID, String targetCompID);

    SerializerFixWriterBuilder getWriter(String senderCompID, String targetCompID);
}
