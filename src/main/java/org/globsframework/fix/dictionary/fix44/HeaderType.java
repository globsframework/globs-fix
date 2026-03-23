package org.globsframework.fix.dictionary.fix44;

import org.globsframework.core.metamodel.GlobType;
import org.globsframework.core.metamodel.GlobTypeBuilder;
import org.globsframework.core.metamodel.GlobTypeBuilderFactory;
import org.globsframework.fix.dictionary.model.FixMessageType;

public class HeaderType {
    public static final GlobType TYPE;


    static {
        GlobTypeBuilder builder = GlobTypeBuilderFactory.create("Header");
        builder.addAnnotation(FixMessageType.create("Header"));
        TYPE = builder.build();
    }
}
