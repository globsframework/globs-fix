package org.globsframework.fix.dictionary;

import org.globsframework.core.metamodel.GlobType;
import org.globsframework.core.metamodel.fields.Field;

public interface GlobFixModel {

    record FixType(GlobType type, FieldFix fieldFix){
    }

    interface FieldFix {
        Field getField(int id);
    }

    FixType getHeader();
    FixType getTrailer();
    FixType getMessage(String name);
}
