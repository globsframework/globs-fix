package org.globsframework.fix.deserializer;

import org.globsframework.core.metamodel.fields.GlobArrayField;
import org.globsframework.core.model.Glob;
import org.globsframework.core.model.MutableGlob;
import org.globsframework.core.model.globaccessor.set.GlobSetGlobArrayAccessor;

record FieldGroupReader(GlobArrayField<?> globArrayField, GlobSetGlobArrayAccessor accessor,
                        FixStruct fixStruct) implements GroupReader {

    @Override
    public FixStruct sub() {
        return fixStruct;
    }

    @Override
    public void update(Glob[] group, MutableGlob data) {
        accessor.set(data, group);
    }

    @Override
    public boolean isSet(Glob data, int currentReadId) {
        final boolean set = data.isSet(globArrayField);
        if (!set) {
            return false;
        }
        final Glob[] globs = data.get(globArrayField);
        if (globs.length == 0) {
            return false;
        }
        final FieldReader fieldReader = fixStruct.getFieldReader(currentReadId);
        if (fieldReader == null) {
            return true; // for field 'no' number of elements in group we force isSet = true
        }
        return fieldReader.isSet(globs[globs.length - 1], currentReadId);
    }
}
