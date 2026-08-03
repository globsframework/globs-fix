package org.globsframework.fix.deserializer;

import org.globsframework.core.metamodel.fields.GlobField;
import org.globsframework.core.model.Glob;
import org.globsframework.core.model.MutableGlob;

class ComponentReaderImpl implements ComponentReader {
    private final FixStruct fixStruct;
    private final GlobField<?> globField;

    public ComponentReaderImpl(FixStruct fixStruct, GlobField<?> globField) {
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
    public MutableGlob get(MutableGlob data) {
        return data.getMutable(globField);
    }

    @Override
    public boolean isSet(Glob data, int currentReadId) {
        return data.isSet(globField) &&
               fixStruct.getFieldReader(currentReadId).isSet(data.get(globField), currentReadId);
    }
}
