package org.globsframework.fix.serializer;

import org.globsframework.core.model.Glob;
import org.globsframework.core.model.globaccessor.get.GlobGetStringAccessor;
import org.globsframework.fix.Utils;

import java.nio.charset.StandardCharsets;

record SmallStringFieldWrite(byte[] id, GlobGetStringAccessor accessor) implements FieldWrite {

    public SmallStringFieldWrite(int id, GlobGetStringAccessor getAccessor) {
        this(Integer.toString(id).getBytes(StandardCharsets.ISO_8859_1), getAccessor);
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
        int l;
        if (s != null && (l = s.length()) > 0) {
            final byte[] buffer = out.buffer;
            int at = Utils.transfert(buffer, out.at, id);
            buffer[at++] = '=';
            if (l == 1) {
                buffer[at++] = (byte) s.charAt(0);
            } else {
                at = Utils.transfert(buffer, at, s);
            }
            buffer[at++] = 0x1;
            out.at = at;
        }
    }
}
