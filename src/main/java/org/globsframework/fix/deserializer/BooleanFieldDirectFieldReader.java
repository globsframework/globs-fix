package org.globsframework.fix.deserializer;

import org.globsframework.core.metamodel.fields.BooleanField;
import org.globsframework.core.model.Glob;
import org.globsframework.core.model.MutableGlob;
import org.globsframework.core.model.globaccessor.set.GlobSetBooleanAccessor;

record BooleanFieldDirectFieldReader(BooleanField booleanField, GlobSetBooleanAccessor accessor) implements DirectFieldReader {

    @Override
    public boolean isSet(Glob data, int currentReadId) {
        return data.isSet(booleanField);
    }

    @Override
    public void read(int from, int to, byte[] buffer, MutableGlob data) {
        accessor.setNative(data, buffer[from] == (byte) 'Y');
    }
}
