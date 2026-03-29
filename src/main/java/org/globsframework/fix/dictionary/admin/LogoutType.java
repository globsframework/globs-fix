package org.globsframework.fix.dictionary.admin;

import org.globsframework.core.metamodel.GlobType;
import org.globsframework.core.metamodel.GlobTypeBuilder;
import org.globsframework.core.metamodel.GlobTypeBuilderFactory;
import org.globsframework.core.metamodel.fields.IntegerField;
import org.globsframework.core.metamodel.fields.StringField;
import org.globsframework.core.model.Glob;
import org.globsframework.fix.dictionary.model.FixFieldType;
import org.globsframework.fix.dictionary.model.FixMessageType;

public class LogoutType {
    public static final GlobType TYPE;

    public static final StringField text;
    public static final IntegerField encodedTextLen;
    public static final StringField encodedText;

    static {
        GlobTypeBuilder builder = GlobTypeBuilderFactory.create("Logout");
        builder.addAnnotation(FixMessageType.create("Logout"));
        text = builder.declareStringField("text", FixFieldType.create("Text"));
        encodedTextLen = builder.declareIntegerField("encodedTextLen", FixFieldType.create("EncodedTextLen"));
        encodedText = builder.declareStringField("encodedText", FixFieldType.create("EncodedText"));
        TYPE = builder.build();
    }

    public static Glob create(String message) {
        return TYPE.instantiate().set(text, message);
    }
}
