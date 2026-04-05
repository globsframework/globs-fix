package org.globsframework.fix.deserializer;

import org.globsframework.core.metamodel.fields.GlobField;
import org.globsframework.core.model.Glob;
import org.globsframework.core.model.MutableGlob;

class ComponentReaderImpl implements ComponentReader {
    private final FixStruct fixStruct;
    private final GlobField globField;

    public ComponentReaderImpl(FixStruct fixStruct, GlobField globField) {
        this.fixStruct = fixStruct;
        this.globField = globField;
    }

    @Override
    public FixStruct getComponent() {
        return fixStruct;
    }

    @Override
    public void update(Glob glob, MutableGlob data) {
        data.set(globField, glob);
    }

    @Override
    public boolean isSet(MutableGlob data) {
        return data.isSet(globField);
    }
}
