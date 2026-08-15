package org.globsframework.fix.deserializer;

import org.globsframework.core.metamodel.fields.GlobField;
import org.globsframework.core.model.Glob;
import org.globsframework.core.model.MutableGlob;

record ComponentReaderImpl(FixStruct fixStruct, GlobField<?> globField) implements ComponentReader {

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
