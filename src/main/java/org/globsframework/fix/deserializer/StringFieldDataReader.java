package org.globsframework.fix.deserializer;

import org.globsframework.core.metamodel.fields.StringField;
import org.globsframework.core.model.Glob;
import org.globsframework.core.model.MutableGlob;

import java.nio.charset.StandardCharsets;

/*
A DATA field bound to a StringField : kept for the types written before DATA was mapped to a
BytesField. Binary content that is not ISO-8859-1 text should use a BytesField.
 */
class StringFieldDataReader implements DataFieldReader {
    private final StringField field;

    public StringFieldDataReader(StringField field) {
        this.field = field;
    }

    @Override
    public void read(byte[] payload, MutableGlob data) {
        data.set(field, new String(payload, StandardCharsets.ISO_8859_1));
    }

    @Override
    public boolean isSet(Glob data, int currentReadId) {
        return data.isSet(field);
    }
}
