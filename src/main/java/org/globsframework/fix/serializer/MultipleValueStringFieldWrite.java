package org.globsframework.fix.serializer;

import org.globsframework.core.model.Glob;
import org.globsframework.core.model.globaccessor.get.GlobGetStringAccessor;
import org.globsframework.core.model.globaccessor.get.GlobGetStringArrayAccessor;
import org.globsframework.core.utils.Strings;
import org.globsframework.fix.Utils;
import org.globsframework.fix.dictionary.FixField;

import java.nio.charset.StandardCharsets;

final class MultipleValueStringFieldWrite implements FieldWrite {
    private final GlobGetStringArrayAccessor accessor;
    private final byte[] id;

    public MultipleValueStringFieldWrite(int id, GlobGetStringArrayAccessor getAccessor) {
        this.accessor = getAccessor;
        this.id = Integer.toString(id).getBytes(StandardCharsets.ISO_8859_1);
    }

    public static FieldWrite create(FixField fixField, int id, GlobGetStringArrayAccessor getAccessor) {
        if (fixField.getMaxEnumLength() == 1) {
            return new MultipleValueStringFieldWrite(id, getAccessor);
        }
        return new MultipleValueStringFieldWrite(id, getAccessor);
    }

    @Override
    public int writeAt(byte[] buffer, int at, Glob data) {
        final String[] s = accessor.get(data);
        if (s != null && s.length > 0) {
            final int start = at;
            at = Utils.transfert(buffer, at, id);
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
                return start; // only empty values : write nothing
            }
            at--; // overwrite 0x20
            buffer[at++] = 0x1;
        }
        return at;
    }
}
