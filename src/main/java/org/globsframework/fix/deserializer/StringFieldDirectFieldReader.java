package org.globsframework.fix.deserializer;

import org.globsframework.core.metamodel.fields.StringField;
import org.globsframework.core.model.Glob;
import org.globsframework.core.model.MutableGlob;

import java.nio.charset.StandardCharsets;

record StringFieldDirectFieldReader(StringField field) implements DirectFieldReader {

    @Override
    public void read(int from, int to, byte[] buffer, MutableGlob data) {
        data.set(field, new String(buffer, from, to - from, StandardCharsets.ISO_8859_1));
    }

    @Override
    public boolean isSet(Glob data, int currentReadId) {
        return data.isSet(field);
    }
}
