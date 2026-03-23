package org.globsframework.fix.deserializer;

import org.globsframework.core.model.Glob;

public record FixMessageValue(Glob header, Glob message, Glob trailer) {
}
