package org.globsframework.fix.serializer;

import org.globsframework.core.model.Glob;
import org.globsframework.core.model.globaccessor.get.GlobGetAccessor;
import org.globsframework.core.model.globaccessor.get.GlobGetIntAccessor;
import org.globsframework.core.model.globaccessor.get.GlobGetStringAccessor;

import java.nio.charset.StandardCharsets;

class IntegerFieldWrite implements FieldWrite {
    GlobGetIntAccessor accessor;
    byte[] id;

    public IntegerFieldWrite(int id, GlobGetIntAccessor getAccessor) {
        this.id = Integer.toString(id).getBytes(StandardCharsets.US_ASCII);
        accessor = getAccessor;
    }

    @Override
    public int writeAt(byte[] buffer, int at, Glob data) {
        final Integer value = accessor.get(data);
        if (value != null) {
            for (byte b : id) {
                buffer[at++] = b;
            }
            buffer[at++] = '=';
            final byte[] bytes = Integer.toString(value).getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(bytes, 0, buffer, at, bytes.length);
            at += bytes.length;
            buffer[at++] = 0x1;
        }
        return at;
    }
}
