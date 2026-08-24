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
    public void writeAt(WriteBuffer out, Glob data) {
        write(out, id, accessor.get(data));
    }

    @Override
    public void call(boolean isSet, boolean isNull, Object value, WriteBuffer out, Void unused) {
        write(out, id, (Boolean) value);
    }

    private static void write(WriteBuffer out, byte[] id, Boolean value) {
        if (value != null) {
            final byte[] buffer = out.buffer;
            int at = Utils.transfert(buffer, out.at, id);
            buffer[at++] = '=';
            buffer[at++] = (byte) (value ? 'Y' : 'N');
            buffer[at++] = 0x1;
            out.at = at;
        }
    }
}
