package org.globsframework.fix.serializer;

import org.globsframework.core.metamodel.GlobType;
import org.globsframework.core.metamodel.fields.Field;
import org.globsframework.core.model.Glob;

import java.util.Arrays;
import java.util.Map;

public final class MessageFieldWrite {
    private final FieldWrite[] writes;
    private final FieldWrite[] indexedWrites;

    public MessageFieldWrite(GlobType type, Map<Field, FieldWrite> writes) {
        this.writes = writes.values().toArray(new FieldWrite[0]);
        final int max = Arrays.stream(type.getFields()).map(Field::getIndex).max(Integer::compareTo).get();
        indexedWrites = new FieldWrite[max + 1];
        for (Map.Entry<Field, FieldWrite> fieldFieldWriteEntry : writes.entrySet()) {
            indexedWrites[fieldFieldWriteEntry.getKey().getIndex()] = fieldFieldWriteEntry.getValue();
        }
    }

    public int writeAt(byte[] buffer, int at, Glob data, short[] fields, int size) {
        for (int i = 0; i < size; i++) {
            at = indexedWrites[fields[i]].writeAt(buffer, at, data);
        }
        return at;
    }

    public int writeAt(byte[] buffer, int at, Glob data) {
        for (FieldWrite fieldWrite : writes) {
            at = fieldWrite.writeAt(buffer, at, data);
        }
        return at;
    }
}
