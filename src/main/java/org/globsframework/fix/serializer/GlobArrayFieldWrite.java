package org.globsframework.fix.serializer;

import org.globsframework.core.model.Glob;
import org.globsframework.core.model.globaccessor.get.GlobGetGlobArrayAccessor;

import java.nio.charset.StandardCharsets;

class GlobArrayFieldWrite implements FieldWrite {
    byte[] id;
    GlobGetGlobArrayAccessor accessor;
    FieldWrite globFieldWrite;

    @Override
    public int writeAt(byte[] buffer, int at, Glob data) {
        final Glob[] globs = accessor.get(data);
        if (globs != null && globs.length > 0) {
            for (byte b : id) {
                buffer[at++] = b;
            }
            buffer[at++] = '=';
            int size = globs.length;
            if (size < 10) {
                buffer[at++] = (byte) ('0' + size);
            } else if (size < 100) {
                buffer[at++] = (byte) ('0' + (size / 10));
                buffer[at++] = (byte) ('0' + size - (size / 10) * 10);
            } else {
                final byte[] val = Integer.toString(size).getBytes(StandardCharsets.US_ASCII);
                System.arraycopy(val, 0, buffer, at, val.length);
            }
            buffer[at++] = 0x1;
            for (Glob glob : globs) {
                at = globFieldWrite.writeAt(buffer, at, glob);
            }
        }
        return at;
    }
}
