package org.globsframework.fix.serializer;

import org.globsframework.core.model.Glob;
import org.globsframework.core.model.globaccessor.get.GlobGetBytesAccessor;
import org.globsframework.core.model.globaccessor.get.GlobGetStringAccessor;
import org.globsframework.fix.Utils;

import java.nio.charset.StandardCharsets;

/*
A DATA field and the LENGTH field that precedes it are written as one : the length is always derived
from the payload, never read from the glob, and neither is written when there is no payload. Writing
them together also makes them immune to the order the writers are held in.
 */
abstract class DataFieldWrite implements FieldWrite {
    private final byte[] lengthId;
    private final byte[] dataId;

    private DataFieldWrite(int lengthId, int dataId) {
        this.lengthId = Integer.toString(lengthId).getBytes(StandardCharsets.ISO_8859_1);
        this.dataId = Integer.toString(dataId).getBytes(StandardCharsets.ISO_8859_1);
    }

    static FieldWrite create(int lengthId, int dataId, GlobGetBytesAccessor accessor) {
        return new BytesDataFieldWrite(lengthId, dataId, accessor);
    }

    static FieldWrite create(int lengthId, int dataId, GlobGetStringAccessor accessor) {
        return new StringDataFieldWrite(lengthId, dataId, accessor);
    }

    /**
     * @return the index at which the payload of len bytes must be written, -1 when there is nothing to write.
     */
    final int writeLength(byte[] buffer, int at, int len) {
        if (at + len + lengthId.length + dataId.length + 16 > buffer.length) {
            throw new RuntimeException("Data field " + new String(dataId, StandardCharsets.ISO_8859_1) + " of " + len +
                                       " bytes does not fit in the " + buffer.length + " byte write buffer.");
        }
        at = Utils.transfert(buffer, at, lengthId);
        buffer[at++] = '=';
        at = Utils.transfertInt(buffer, at, len);
        buffer[at++] = 0x1;
        at = Utils.transfert(buffer, at, dataId);
        buffer[at++] = '=';
        return at;
    }

    private static final class BytesDataFieldWrite extends DataFieldWrite {
        private final GlobGetBytesAccessor accessor;

        BytesDataFieldWrite(int lengthId, int dataId, GlobGetBytesAccessor accessor) {
            super(lengthId, dataId);
            this.accessor = accessor;
        }

        public int writeAt(byte[] buffer, int at, Glob data) {
            final byte[] value = accessor.get(data);
            if (value == null) {
                return at;
            }
            at = writeLength(buffer, at, value.length);
            at = Utils.transfert(buffer, at, value);
            buffer[at++] = 0x1;
            return at;
        }
    }

    /*
    A DATA field bound to a StringField : one ISO-8859-1 byte per character, so the length is the
    length of the string.
     */
    private static final class StringDataFieldWrite extends DataFieldWrite {
        private final GlobGetStringAccessor accessor;

        StringDataFieldWrite(int lengthId, int dataId, GlobGetStringAccessor accessor) {
            super(lengthId, dataId);
            this.accessor = accessor;
        }

        public int writeAt(byte[] buffer, int at, Glob data) {
            final String value = accessor.get(data);
            if (value == null) {
                return at;
            }
            at = writeLength(buffer, at, value.length());
            at = Utils.transfert(buffer, at, value);
            buffer[at++] = 0x1;
            return at;
        }
    }
}
