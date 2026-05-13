package org.globsframework.fix.deserializer;

import org.globsframework.core.metamodel.GlobType;
import org.globsframework.core.utils.collections.IntHashMap;

public class FixStructImpl implements FixStruct {
    private final GlobType type;
    private final FieldReader[] fieldReaders;

    public FixStructImpl(GlobType type, IntHashMap<FieldReader> fieldReadersMap) {
        this.type = type;
        int max = fieldReadersMap.keySet().stream().max(Integer::compare).orElse(0);
        fieldReaders = new FieldReader[max + 1];
        for (IntHashMap.Entry<FieldReader> fieldReaderEntry : fieldReadersMap.entrySet()) {
            fieldReaders[fieldReaderEntry.getKey()] = fieldReaderEntry.getValue();
        }
    }

    @Override
    public GlobType getType() {
        return type;
    }

    @Override
    public FieldReader getFieldReader(int id) {
        if (id > fieldReaders.length - 1 || id < 0) {
            return null;
        }
        return fieldReaders[id];
    }
}
