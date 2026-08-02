package org.globsframework.fix.deserializer;

import org.globsframework.core.metamodel.fields.IntegerField;
import org.globsframework.core.model.Glob;
import org.globsframework.core.model.MutableGlob;
import org.globsframework.fix.Utils;

class IntFieldDataLengthReader implements DataLengthFieldReader {
    private final IntegerField field;
    private final int dataTag;

    public IntFieldDataLengthReader(IntegerField field, int dataTag) {
        this.field = field;
        this.dataTag = dataTag;
    }

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
