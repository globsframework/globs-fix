package org.globsframework.fix.engine;

import org.globsframework.fix.deserializer.DeserializerFixReaderBuilder;
import org.globsframework.fix.serializer.SerializerFixWriterBuilder;

class DefaultSerializerProvider implements SerializerProvider {
    private final DeserializerFixReaderBuilder deserializerFixReaderBuilder;
    private final SerializerFixWriterBuilder serializerFixWriterBuilder;
    private final HeaderDesc headerDesc;

    public DefaultSerializerProvider(DeserializerFixReaderBuilder deserializerFixReaderBuilder, SerializerFixWriterBuilder serializerFixWriterBuilder, HeaderDesc headerDesc) {
        this.deserializerFixReaderBuilder = deserializerFixReaderBuilder;
        this.serializerFixWriterBuilder = serializerFixWriterBuilder;
        this.headerDesc = headerDesc;
    }

    @Override
    public HeaderDesc getHeaderDesc(String senderCompID, String targetCompID) {
        return headerDesc;
    }

    @Override
    public DeserializerFixReaderBuilder getReader(String senderCompID, String targetCompID) {
        return deserializerFixReaderBuilder;
    }

    @Override
    public SerializerFixWriterBuilder getWriter(String senderCompID, String targetCompID) {
        return serializerFixWriterBuilder;
    }
}
