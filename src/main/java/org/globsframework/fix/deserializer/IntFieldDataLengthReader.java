package org.globsframework.fix.deserializer;

import org.globsframework.core.metamodel.fields.IntegerField;
import org.globsframework.core.model.Glob;
import org.globsframework.core.model.MutableGlob;
import org.globsframework.core.model.globaccessor.set.GlobSetIntAccessor;
import org.globsframework.fix.Utils;

record IntFieldDataLengthReader(IntegerField field, GlobSetIntAccessor accessor,
                                int dataTag) implements DataLengthFieldReader {

    @Override
    public int dataTag() {
        return dataTag;
    }

    @Override
    public void read(int from, int to, byte[] buffer, MutableGlob data) {
        accessor.setNative(data, Utils.getIntAt(from, to, buffer));
    }

    @Override
    public boolean isSet(Glob data, int currentReadId) {
        return data.isSet(field);
    }
}
