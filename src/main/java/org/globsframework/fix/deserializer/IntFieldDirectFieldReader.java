package org.globsframework.fix.deserializer;

import org.globsframework.core.metamodel.fields.IntegerField;
import org.globsframework.core.model.MutableGlob;
import org.globsframework.fix.Utils;

class IntFieldDirectFieldReader implements DirectFieldReader {
    private final IntegerField integerField;

    public IntFieldDirectFieldReader(IntegerField integerField) {
        this.integerField = integerField;
    }

    @Override
    public boolean isSet(MutableGlob data) {
        return data.isSet(integerField);
    }

    @Override
    public void read(int from, int to, byte[] buffer, MutableGlob data) {
        data.set(integerField, Utils.getIntAt(from, to, buffer));
    }
}
