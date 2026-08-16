package org.globsframework.fix.deserializer;

import org.globsframework.core.metamodel.fields.StringField;
import org.globsframework.core.model.Glob;
import org.globsframework.core.model.MutableGlob;
import org.globsframework.core.model.globaccessor.set.GlobSetStringAccessor;

import java.nio.charset.StandardCharsets;

/*
A DATA field bound to a StringField : kept for the types written before DATA was mapped to a
BytesField. Binary content that is not ISO-8859-1 text should use a BytesField.
 */
record StringFieldDataReader(StringField field, GlobSetStringAccessor accessor) implements DataFieldReader {

    @Override
    public void read(byte[] payload, MutableGlob data) {
        accessor.set(data, new String(payload, StandardCharsets.ISO_8859_1));
    }

    @Override
    public boolean isSet(Glob data, int currentReadId) {
        return data.isSet(field);
    }
}
