package org.globsframework.fix.serializer;

import org.globsframework.core.model.Glob;

public interface FieldWrite {
    void writeAt(WriteBuffer out, Glob data);
}
