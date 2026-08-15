package org.globsframework.fix.serializer;

import org.globsframework.core.model.Glob;
import org.globsframework.core.model.globaccessor.get.GlobGetBooleanAccessor;
import org.globsframework.fix.Utils;

import java.nio.charset.StandardCharsets;

record BooleanFieldWrite(byte[] id, GlobGetBooleanAccessor accessor) implements FieldWrite {

    public BooleanFieldWrite(int id, GlobGetBooleanAccessor getAccessor) {
        this(Integer.toString(id).getBytes(StandardCharsets.ISO_8859_1), getAccessor);
    }

    @Override
    public int writeAt(byte[] buffer, int at, Glob data) {
        final Boolean value = accessor.get(data);
        if (value != null) {
            at = Utils.transfert(buffer, at, id);
            buffer[at++] = '=';
            buffer[at++] = (byte) (value ? 'Y' : 'N');
            buffer[at++] = 0x1;
        }
        return at;
    }
}
