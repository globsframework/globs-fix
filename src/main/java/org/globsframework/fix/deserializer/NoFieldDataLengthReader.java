package org.globsframework.fix.deserializer;

import org.globsframework.core.model.Glob;
import org.globsframework.core.model.MutableGlob;

/*
The GlobType does not bind the length, but its value still drives the reading of the data field
that follows, so the tag cannot simply be skipped.
 */
record NoFieldDataLengthReader(int dataTag) implements DataLengthFieldReader {

    @Override
    public int dataTag() {
        return dataTag;
    }

    @Override
    public void read(int from, int to, byte[] buffer, MutableGlob data) {
    }

    @Override
    public boolean isSet(Glob data, int currentReadId) {
        return false;
    }
}
