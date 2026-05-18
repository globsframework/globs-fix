package org.globsframework.fix.deserializer;

import org.globsframework.core.metamodel.GlobType;

public record FixMessageStructure(String name,
                                  GlobType type,
                                  FixStruct fixStruct) {
}
