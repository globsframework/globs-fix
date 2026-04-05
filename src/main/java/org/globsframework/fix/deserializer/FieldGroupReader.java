package org.globsframework.fix.deserializer;

import org.globsframework.core.metamodel.fields.GlobArrayField;
import org.globsframework.core.model.Glob;
import org.globsframework.core.model.MutableGlob;

class FieldGroupReader implements GroupReader {
    private final GlobArrayField globArrayField;
    private final FixStruct fixStruct;

    public FieldGroupReader(GlobArrayField globArrayField, FixStruct fixStruct) {
        this.globArrayField = globArrayField;
        this.fixStruct = fixStruct;
    }

    @Override
    public FixStruct sub() {
        return fixStruct;
    }

    @Override
    public void update(Glob[] group, MutableGlob data) {
        data.set(globArrayField, group);
    }

    @Override
    public boolean isSet(MutableGlob data) {
        return data.isSet(globArrayField);
    }
}
