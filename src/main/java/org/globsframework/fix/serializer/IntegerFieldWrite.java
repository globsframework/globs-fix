package org.globsframework.fix.serializer;

import org.globsframework.core.model.Glob;
import org.globsframework.core.model.globaccessor.get.GlobGetIntAccessor;
import org.globsframework.fix.Utils;

import java.nio.charset.StandardCharsets;

final class IntegerFieldWrite implements FieldWrite {
    private final GlobGetIntAccessor accessor;
    private final byte[] id;

    public IntegerFieldWrite(int id, GlobGetIntAccessor getAccessor) {
        this.id = Integer.toString(id).getBytes(StandardCharsets.ISO_8859_1);
        accessor = getAccessor;
    }

    @Override
    public int writeAt(byte[] buffer, int at, Glob data) {
        final Integer value = accessor.get(data);
        if (value != null) {
            at = Utils.transfert(buffer, at, id);
            buffer[at++] = '=';
            at = Utils.transfertInt(buffer, at, value);
            buffer[at++] = 0x1;
        }
        return at;
    }
}
