package org.globsframework.fix.deserializer;

import org.globsframework.core.model.Glob;
import org.globsframework.core.model.MutableGlob;

class NoFieldComponentReaderImpl implements ComponentReader {
    private final FixStruct fixStruct;

    public NoFieldComponentReaderImpl(FixStruct fixStruct) {
        this.fixStruct = fixStruct;
    }

    @Override
    public FixStruct getComponent() {
        return fixStruct;
    }

    @Override
    public void update(Glob glob, MutableGlob data) {
    }

    @Override
    public MutableGlob get(MutableGlob data) {
        return null;
    }

    @Override
    public boolean isSet(Glob data, int currentReadId) {
        return false;
    }
}
