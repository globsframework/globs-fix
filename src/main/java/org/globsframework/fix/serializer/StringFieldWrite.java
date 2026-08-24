package org.globsframework.fix.serializer;

import org.globsframework.core.model.Glob;
import org.globsframework.core.model.globaccessor.get.GlobGetStringAccessor;
import org.globsframework.fix.Utils;
import org.globsframework.fix.dictionary.FixField;

import java.nio.charset.StandardCharsets;

record StringFieldWrite(byte[] id, GlobGetStringAccessor accessor) implements FieldWrite {

    public StringFieldWrite(int id, GlobGetStringAccessor getAccessor) {
        this(Integer.toString(id).getBytes(StandardCharsets.ISO_8859_1), getAccessor);
    }

    public static FieldWrite create(FixField fixField, int id, GlobGetStringAccessor getAccessor) {
        if (fixField.getMaxEnumLength() == 1) {
            return new SmallStringFieldWrite(id, getAccessor);
        }
        return new StringFieldWrite(id, getAccessor);
    }

    @Override
    public void writeAt(WriteBuffer out, Glob data) {
        write(out, id, accessor.get(data));
    }

    @Override
    public void call(boolean isSet, boolean isNull, Object value, WriteBuffer out, Void unused) {
        write(out, id, (String) value);
    }

    private static void write(WriteBuffer out, byte[] id, String s) {
        if (s != null) {
            final byte[] buffer = out.buffer;
            int at = Utils.transfert(buffer, out.at, id);
            buffer[at++] = '=';
            at = Utils.transfert(buffer, at, s);
            buffer[at++] = 0x1;
            out.at = at;
        }
    }
}
