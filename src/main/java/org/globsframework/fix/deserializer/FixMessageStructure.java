package org.globsframework.fix.deserializer;

import org.globsframework.core.metamodel.GlobType;

public record FixMessageStructure(String fixCode,
                                  GlobType type,
                                  FixStruct fixStruct) {
}
