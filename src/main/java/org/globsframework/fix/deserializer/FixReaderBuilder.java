package org.globsframework.fix.deserializer;

public interface FixReaderBuilder {
    FixReader createReader(ByteReader reader);

    FixReader createReader(ByteReader reader, byte[] initialBuffer, int len);
}
