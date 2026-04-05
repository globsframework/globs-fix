package org.globsframework.fix.serializer;

import org.globsframework.core.model.Glob;
import org.globsframework.core.model.globaccessor.get.GlobGetStringAccessor;
import org.globsframework.fix.Utils;

import java.nio.charset.StandardCharsets;

final class StringFieldWrite implements FieldWrite {
    private final GlobGetStringAccessor accessor;
    private final byte[] id;

    public StringFieldWrite(int id, GlobGetStringAccessor getAccessor) {
        this.accessor = getAccessor;
        this.id = Integer.toString(id).getBytes(StandardCharsets.US_ASCII);
    }

    @Override
    public int writeAt(byte[] buffer, int at, Glob data) {
        final String s = accessor.get(data);
        if (s != null) {
            at = Utils.fastCopy(buffer, at, id);
            buffer[at++] = '=';
            at = Utils.fastCopy(buffer, at, s);
            buffer[at++] = 0x1;
        }
        return at;
    }

}
