package org.globsframework.fix.serializer;

import org.globsframework.core.model.Glob;

public interface FixWriter {
    void write(Glob header, Glob message, Glob trailer);
}
