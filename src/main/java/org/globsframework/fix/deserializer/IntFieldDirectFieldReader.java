package org.globsframework.fix.deserializer;

import org.globsframework.core.metamodel.fields.IntegerField;
import org.globsframework.core.model.Glob;
import org.globsframework.core.model.MutableGlob;
import org.globsframework.fix.Utils;

record IntFieldDirectFieldReader(IntegerField integerField) implements DirectFieldReader {

    @Override
    public boolean isSet(Glob data, int currentReadId) {
        return data.isSet(integerField);
    }

    @Override
    public void read(int from, int to, byte[] buffer, MutableGlob data) {
        data.set(integerField, Utils.getIntAt(from, to, buffer));
    }
}
