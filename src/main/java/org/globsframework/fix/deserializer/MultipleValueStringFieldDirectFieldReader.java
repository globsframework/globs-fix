package org.globsframework.fix.deserializer;

import org.globsframework.core.metamodel.fields.StringArrayField;
import org.globsframework.core.model.Glob;
import org.globsframework.core.model.MutableGlob;
import org.globsframework.core.model.globaccessor.set.GlobSetStringArrayAccessor;

import java.nio.charset.StandardCharsets;

record MultipleValueStringFieldDirectFieldReader(StringArrayField field,
                                                 GlobSetStringArrayAccessor accessor) implements DirectFieldReader {

    @Override
    public void read(int from, int to, byte[] buffer, MutableGlob data) {
        final String s = new String(buffer, from, to - from, StandardCharsets.ISO_8859_1);
        accessor.set(data, s.split(" ")); //TODO
    }

    @Override
    public boolean isSet(Glob data, int currentReadId) {
        return data.isSet(field);
    }
}
