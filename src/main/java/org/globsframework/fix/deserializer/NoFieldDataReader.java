package org.globsframework.fix.deserializer;

import org.globsframework.core.model.Glob;
import org.globsframework.core.model.MutableGlob;

final class NoFieldDataReader implements DataFieldReader {

    @Override
    public void read(byte[] payload, MutableGlob data) {
    }

    @Override
    public boolean isSet(Glob data, int currentReadId) {
        return false;
    }
}
