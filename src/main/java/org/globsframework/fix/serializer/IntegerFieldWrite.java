package org.globsframework.fix.serializer;

import org.globsframework.core.model.Glob;
import org.globsframework.core.model.globaccessor.get.GlobGetAccessor;
import org.globsframework.core.model.globaccessor.get.GlobGetIntAccessor;
import org.globsframework.core.model.globaccessor.get.GlobGetStringAccessor;
import org.globsframework.fix.Utils;

import java.nio.charset.StandardCharsets;

final class IntegerFieldWrite implements FieldWrite {
    private final GlobGetIntAccessor accessor;
    private final byte[] id;

    public IntegerFieldWrite(int id, GlobGetIntAccessor getAccessor) {
        this.id = Integer.toString(id).getBytes(StandardCharsets.US_ASCII);
        accessor = getAccessor;
    }

    @Override
    public int writeAt(byte[] buffer, int at, Glob data) {
        final Integer value = accessor.get(data);
        if (value != null) {
            at = Utils.fastCopy(buffer, at, id);
            buffer[at++] = '=';
            at = Utils.fastCopy(buffer, at, value);
            buffer[at++] = 0x1;
        }
        return at;
    }
}
