package org.globsframework.fix.deserializer;

import org.globsframework.core.model.MutableGlob;

non-sealed interface DirectFieldReader extends FieldReader {
    void read(int from, int to, byte[] buffer, MutableGlob data);
}
