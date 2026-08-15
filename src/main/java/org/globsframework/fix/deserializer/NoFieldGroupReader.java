package org.globsframework.fix.deserializer;

import org.globsframework.core.model.Glob;
import org.globsframework.core.model.MutableGlob;

record NoFieldGroupReader(FixStruct fixStruct) implements GroupReader {

    @Override
    public FixStruct sub() {
        return fixStruct;
    }

    @Override
    public void update(Glob[] group, MutableGlob data) {
    }

    @Override
    public boolean isSet(Glob data, int currentReadId) {
        return false;
    }
}
