package org.globsframework.fix.serializer;

import org.globsframework.core.model.Glob;
import org.globsframework.core.model.globaccessor.get.GlobGetBooleanAccessor;
import org.globsframework.core.model.globaccessor.get.GlobGetIntAccessor;
import org.globsframework.fix.Utils;

import java.nio.charset.StandardCharsets;

class BooleanFieldWrite implements FieldWrite {
    GlobGetBooleanAccessor accessor;
    byte[] id;

    public BooleanFieldWrite(int id, GlobGetBooleanAccessor getAccessor) {
        this.id = Integer.toString(id).getBytes(StandardCharsets.US_ASCII);
        accessor = getAccessor;
    }

    @Override
    public int writeAt(byte[] buffer, int at, Glob data) {
        final Boolean value = accessor.get(data);
        if (value != null) {
            at = Utils.fastCopy(buffer, at, id);
            buffer[at++] = '=';
            buffer[at++] = (byte) (value ? 'Y' : 'N');
            buffer[at++] = 0x1;
        }
        return at;
    }
}
