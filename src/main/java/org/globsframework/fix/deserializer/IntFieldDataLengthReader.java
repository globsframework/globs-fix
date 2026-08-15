package org.globsframework.fix.deserializer;

import org.globsframework.core.metamodel.fields.IntegerField;
import org.globsframework.core.model.Glob;
import org.globsframework.core.model.MutableGlob;
import org.globsframework.fix.Utils;

record IntFieldDataLengthReader(IntegerField field, int dataTag) implements DataLengthFieldReader {

    @Override
    public int dataTag() {
        return dataTag;
    }

    @Override
    public void read(int from, int to, byte[] buffer, MutableGlob data) {
        data.set(field, Utils.getIntAt(from, to, buffer));
    }

    @Override
    public boolean isSet(Glob data, int currentReadId) {
        return data.isSet(field);
    }
}
