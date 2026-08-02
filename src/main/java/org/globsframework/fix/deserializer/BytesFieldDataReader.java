package org.globsframework.fix.deserializer;

import org.globsframework.core.metamodel.fields.BytesField;
import org.globsframework.core.model.Glob;
import org.globsframework.core.model.MutableGlob;

class BytesFieldDataReader implements DataFieldReader {
    private final BytesField field;

    public BytesFieldDataReader(BytesField field) {
        this.field = field;
    }

    @Override
    public void read(byte[] payload, MutableGlob data) {
        data.set(field, payload);
    }

    @Override
    public boolean isSet(Glob data, int currentReadId) {
        return data.isSet(field);
    }
}
