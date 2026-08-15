package org.globsframework.fix.deserializer;

import org.globsframework.core.model.Glob;
import org.globsframework.core.model.MutableGlob;

final class NoFieldDirectFieldReader implements DirectFieldReader {
    @Override
    public void read(int from, int to, byte[] buffer, MutableGlob data) {
    }

    @Override
    public boolean isSet(Glob data, int currentReadId) {
        return false;
    }
}
