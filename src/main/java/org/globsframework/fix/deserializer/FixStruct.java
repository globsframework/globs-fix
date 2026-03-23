package org.globsframework.fix.deserializer;

import org.globsframework.core.metamodel.GlobType;

interface FixStruct {
    GlobType getType();

    FieldReader getFieldReader(int id);
}
