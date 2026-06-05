package org.globsframework.fix.serializer;

import org.globsframework.core.model.Glob;
import org.globsframework.core.model.globaccessor.get.GlobGetStringAccessor;
import org.globsframework.fix.Utils;

import java.nio.charset.StandardCharsets;

final class SmallStringFieldWrite implements FieldWrite {
    private final GlobGetStringAccessor accessor;
    private final byte[] id;

    public SmallStringFieldWrite(int id, GlobGetStringAccessor getAccessor) {
        this.accessor = getAccessor;
        this.id = Integer.toString(id).getBytes(StandardCharsets.ISO_8859_1);
    }

    @Override
    public int writeAt(byte[] buffer, int at, Glob data) {
        final String s = accessor.get(data);
        int l;
        if (s != null && (l = s.length()) > 0 ) {
            at = Utils.transfert(buffer, at, id);
            buffer[at++] = '=';
            if (l == 1) {
                buffer[at++] = (byte)s.charAt(0);
            } else {
                at = Utils.transfert(buffer, at, s);
            }
            buffer[at++] = 0x1;
        }
        return at;
    }

}
