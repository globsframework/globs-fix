package org.globsframework.fix.deserializer;

import org.globsframework.core.model.MutableGlob;

public sealed interface FieldReader permits DirectFieldReader, GroupReader, ComponentReader {
    boolean isSet(MutableGlob data);
}
