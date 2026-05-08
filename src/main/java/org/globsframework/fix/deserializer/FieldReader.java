package org.globsframework.fix.deserializer;

import org.globsframework.core.model.Glob;

public sealed interface FieldReader permits DirectFieldReader, GroupReader, ComponentReader {
    boolean isSet(Glob data, int currentReadId);
}
