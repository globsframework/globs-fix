package org.globsframework.fix.serializer;

import org.globsframework.core.model.Glob;
import org.globsframework.core.model.globaccessor.get.GlobGetStringAccessor;
import org.globsframework.fix.Utils;
import org.globsframework.fix.dictionary.FixField;

import java.nio.charset.StandardCharsets;

final class StringFieldWrite implements FieldWrite {
    private final GlobGetStringAccessor accessor;
    private final byte[] id;

    public StringFieldWrite(int id, GlobGetStringAccessor getAccessor) {
        this.accessor = getAccessor;
        this.id = Integer.toString(id).getBytes(StandardCharsets.ISO_8859_1);
    }

    public static FieldWrite create(FixField fixField, int id, GlobGetStringAccessor getAccessor) {
        if (fixField.getMaxEnumLength() == 1) {
            return new SmallStringFieldWrite(id, getAccessor);
        }
        return new StringFieldWrite(id, getAccessor);
    }

    @Override
    public int writeAt(byte[] buffer, int at, Glob data) {
        final String s = accessor.get(data);
        if (s != null) {
            at = Utils.transfert(buffer, at, id);
            buffer[at++] = '=';
            at = Utils.transfert(buffer, at, s);
            buffer[at++] = 0x1;
        }
        return at;
    }

}
