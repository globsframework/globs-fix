package org.globsframework.fix.deserializer;

import org.globsframework.core.metamodel.fields.BooleanField;
import org.globsframework.core.model.Glob;
import org.globsframework.core.model.MutableGlob;

record BooleanFieldDirectFieldReader(BooleanField booleanField) implements DirectFieldReader {

    @Override
    public boolean isSet(Glob data, int currentReadId) {
        return data.isSet(booleanField);
    }

    @Override
    public void read(int from, int to, byte[] buffer, MutableGlob data) {
        data.set(booleanField, buffer[from] == (byte) 'Y');
    }
}
