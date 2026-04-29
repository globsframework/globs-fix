package org.globsframework.fix;

import org.globsframework.core.metamodel.GlobType;
import org.globsframework.core.metamodel.GlobTypeBuilder;
import org.globsframework.core.metamodel.GlobTypeBuilderFactory;
import org.globsframework.core.metamodel.fields.IntegerField;
import org.globsframework.core.metamodel.fields.StringField;
import org.globsframework.core.model.MutableGlob;
import org.globsframework.fix.dictionary.model.FixFieldType;

public class TrailerType {
    public static final GlobType TYPE;

    public static final StringField Signature;

    public static final IntegerField CheckSum;

    public static MutableGlob create(String signature) {
        return TYPE.instantiate()
                .set(Signature, signature);
    }

    public static MutableGlob create() {
        return TYPE.instantiate();
    }

    static {
        final GlobTypeBuilder typeBuilder = GlobTypeBuilderFactory.create("TrailerType");
        Signature = typeBuilder.declareStringField("Signature",
                FixFieldType.create("Signature"));
        CheckSum = typeBuilder.declareIntegerField("CheckSum", FixFieldType.create("CheckSum"));
        TYPE = typeBuilder.build();
    }
}
