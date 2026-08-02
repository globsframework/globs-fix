package org.globsframework.fix.deserializer;

import org.globsframework.core.metamodel.fields.StringArrayField;
import org.globsframework.core.model.Glob;
import org.globsframework.core.model.MutableGlob;

import java.nio.charset.StandardCharsets;

class MultipleValueStringFieldDirectFieldReader implements DirectFieldReader {
    private final StringArrayField field;

    public MultipleValueStringFieldDirectFieldReader(StringArrayField field) {
        this.field = field;
    }

    @Override
    public void read(int from, int to, byte[] buffer, MutableGlob data) {
        final String s = new String(buffer, from, to - from, StandardCharsets.ISO_8859_1);
        data.set(field, s.split(" ")); //TODO
    }

    @Override
    public boolean isSet(Glob data, int currentReadId) {
        return data.isSet(field);
    }
}
