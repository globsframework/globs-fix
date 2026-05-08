package org.globsframework.fix.deserializer;

import org.globsframework.core.model.Glob;
import org.globsframework.core.model.MutableGlob;

non-sealed interface ComponentReader extends FieldReader {
    FixStruct getComponent();

    void update(Glob glob, MutableGlob data);

    MutableGlob get(MutableGlob data);
}
