package org.globsframework.fix.serializer;

import org.globsframework.core.model.Glob;

public interface FieldWrite {
    int writeAt(byte[] buffer, int at, Glob data);
}
