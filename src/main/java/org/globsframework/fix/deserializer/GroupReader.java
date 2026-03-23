package org.globsframework.fix.deserializer;

import org.globsframework.core.model.Glob;
import org.globsframework.core.model.MutableGlob;

non-sealed interface GroupReader extends FieldReader {
    FixStruct sub();

    void update(Glob[] group, MutableGlob data);
}
