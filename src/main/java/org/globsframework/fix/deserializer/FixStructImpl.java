package org.globsframework.fix.deserializer;

import org.globsframework.core.metamodel.GlobType;
import org.globsframework.core.utils.collections.IntHashMap;

public class FixStructImpl implements FixStruct {
    private final GlobType type;
    private final IntHashMap<FieldReader> fieldReaders;

    public FixStructImpl(GlobType type, IntHashMap<FieldReader> fieldReaders) {
        this.type = type;
        this.fieldReaders = fieldReaders;
    }

    @Override
    public GlobType getType() {
        return type;
    }

    @Override
    public FieldReader getFieldReader(int id) {
        return fieldReaders.get(id);
    }
}
