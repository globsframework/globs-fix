package org.globsframework.fix.deserializer;

import org.globsframework.core.metamodel.fields.StringField;
import org.globsframework.core.model.MutableGlob;

class StringFieldDirectFieldReader implements DirectFieldReader {
    private final StringField field;

    public StringFieldDirectFieldReader(StringField field) {
        this.field = field;
    }

    @Override
    public void read(int from, int to, byte[] buffer, MutableGlob data) {
        data.set(field, new String(buffer, from, to - from));
    }

    @Override
    public boolean isSet(MutableGlob data) {
        return data.isSet(field);
    }
}
