package org.globsframework.fix.deserializer;

import org.globsframework.core.metamodel.fields.StringField;
import org.globsframework.core.model.Glob;
import org.globsframework.core.model.MutableGlob;
import org.globsframework.core.model.globaccessor.set.GlobSetStringAccessor;

import java.nio.charset.StandardCharsets;

record StringFieldDirectFieldReader(StringField field, GlobSetStringAccessor accessor) implements DirectFieldReader {

    @Override
    public void read(int from, int to, byte[] buffer, MutableGlob data) {
        accessor.set(data, new String(buffer, from, to - from, StandardCharsets.ISO_8859_1));
    }

    @Override
    public boolean isSet(Glob data, int currentReadId) {
        return data.isSet(field);
    }
}
