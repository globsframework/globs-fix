package org.globsframework.fix.serializer;

import org.globsframework.core.model.Glob;
import org.globsframework.core.model.globaccessor.get.GlobGetAccessor;
import org.globsframework.core.model.globaccessor.get.GlobGetStringAccessor;

import java.nio.charset.StandardCharsets;

class StringFieldWrite implements FieldWrite {
    private final GlobGetStringAccessor accessor;
    private final byte[] id;

    public StringFieldWrite(int id, GlobGetStringAccessor getAccessor) {
        this.accessor = getAccessor;
        this.id = Integer.toString(id).getBytes(StandardCharsets.US_ASCII);
    }

    @Override
    public int writeAt(byte[] buffer, int at, Glob data) {
        final String s = accessor.get(data);
        if (s != null) {
            for (byte b : id) {
                buffer[at++] = b;
            }
            buffer[at++] = '=';
            final byte[] bytes = s.getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(bytes, 0, buffer, at, bytes.length);
            at += bytes.length;
            buffer[at++] = 0x1;
        }
        return at;
    }
}
