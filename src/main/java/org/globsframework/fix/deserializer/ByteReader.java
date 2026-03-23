package org.globsframework.fix.deserializer;

public interface ByteReader {
    int read(byte[] buf, int offset, int len);
}
