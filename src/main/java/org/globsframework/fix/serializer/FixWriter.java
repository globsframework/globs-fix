package org.globsframework.fix.serializer;

import org.globsframework.core.model.Glob;
import org.globsframework.core.model.MutableGlob;

public interface FixWriter {
    void write(MutableGlob header, Glob message, MutableGlob trailer);
}
