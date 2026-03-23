package org.globsframework.fix.dictionary.model;

import org.globsframework.core.metamodel.GlobType;
import org.globsframework.core.metamodel.GlobTypeBuilder;
import org.globsframework.core.metamodel.GlobTypeBuilderFactory;
import org.globsframework.core.metamodel.fields.IntegerField;
import org.globsframework.core.metamodel.fields.StringField;
import org.globsframework.core.model.Glob;
import org.globsframework.core.model.Key;

public class FixGroupType {
    public static final GlobType TYPE;

    public static final StringField name;

    public static final Glob UNIQUE;

    public static final Key UNIQUE_KEY;

    public static Glob create(String name) {
        return TYPE.instantiate().set(FixGroupType.name, name);
    }

    static {
        GlobTypeBuilder builder = GlobTypeBuilderFactory.create("FixGroupType");
        name = builder.declareStringField("name");
        TYPE = builder.build();
        UNIQUE = TYPE.instantiate();
        UNIQUE_KEY = UNIQUE.getKey();
    }
}
