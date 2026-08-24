package org.globsframework.fix.serializer;

import org.globsframework.core.model.Glob;
import org.globsframework.core.model.globaccessor.get.GlobGetStringArrayAccessor;
import org.globsframework.core.utils.Strings;
import org.globsframework.fix.Utils;
import org.globsframework.fix.dictionary.FixField;

import java.nio.charset.StandardCharsets;

record MultipleValueStringFieldWrite(byte[] id, GlobGetStringArrayAccessor accessor) implements FieldWrite {

    public MultipleValueStringFieldWrite(int id, GlobGetStringArrayAccessor getAccessor) {
        this(Integer.toString(id).getBytes(StandardCharsets.ISO_8859_1), getAccessor);
    }

    public static FieldWrite create(FixField fixField, int id, GlobGetStringArrayAccessor getAccessor) {
        return new MultipleValueStringFieldWrite(id, getAccessor);
    }

    @Override
    public void writeAt(WriteBuffer out, Glob data) {
        write(out, id, accessor.get(data));
    }

    @Override
    public void call(boolean isSet, boolean isNull, Object value, WriteBuffer out, Void unused) {
        write(out, id, (String[]) value);
    }

    private static void write(WriteBuffer out, byte[] id, String[] s) {
        if (s != null && s.length > 0) {
            final byte[] buffer = out.buffer;
            int at = Utils.transfert(buffer, out.at, id);
            buffer[at++] = '=';
            boolean lastIsSpace = false;
            for (String string : s) {
                if (Strings.isNotEmpty(string)) {
                    at = Utils.transfert(buffer, at, string);
                    buffer[at++] = 0x20;
                    lastIsSpace = true;
                }
            }
            if (!lastIsSpace) {
                return; // only empty values : write nothing, at stays where it was
            }
            at--; // overwrite 0x20
            buffer[at++] = 0x1;
            out.at = at;
        }
    }
}
