package org.globsframework.fix.deserializer;

import org.globsframework.core.metamodel.fields.BytesField;
import org.globsframework.core.model.Glob;
import org.globsframework.core.model.MutableGlob;
import org.globsframework.core.model.globaccessor.set.GlobSetBytesAccessor;

record BytesFieldDataReader(BytesField field, GlobSetBytesAccessor accessor) implements DataFieldReader {

    @Override
    public void read(byte[] payload, MutableGlob data) {
        accessor.set(data, payload);
    }

    @Override
    public boolean isSet(Glob data, int currentReadId) {
        return data.isSet(field);
    }
}
