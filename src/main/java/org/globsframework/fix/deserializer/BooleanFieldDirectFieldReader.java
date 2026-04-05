package org.globsframework.fix.deserializer;

import org.globsframework.core.metamodel.fields.BooleanField;
import org.globsframework.core.model.MutableGlob;

class BooleanFieldDirectFieldReader implements DirectFieldReader {
    private final BooleanField booleanField;

    public BooleanFieldDirectFieldReader(BooleanField booleanField) {
        this.booleanField = booleanField;
    }

    @Override
    public boolean isSet(MutableGlob data) {
        return data.isSet(booleanField);
    }

    @Override
    public void read(int from, int to, byte[] buffer, MutableGlob data) {
        data.set(booleanField, buffer[from] == (byte) 'Y');
    }
}
