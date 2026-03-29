package org.globsframework.fix;

import org.globsframework.core.metamodel.GlobType;
import org.globsframework.core.metamodel.GlobTypeBuilder;
import org.globsframework.core.metamodel.GlobTypeBuilderFactory;
import org.globsframework.core.metamodel.fields.StringField;
import org.globsframework.core.model.Glob;
import org.globsframework.fix.dictionary.model.FixFieldType;

public class HeaderType {
    public static final GlobType TYPE;

    public static final StringField SenderCompID;

    public static final StringField TargetCompID;

    public static final StringField MsgType;

    public static Glob create(String senderCompID, String targetCompID) {
        return TYPE.instantiate()
                .set(SenderCompID, senderCompID)
                .set(TargetCompID, targetCompID);
    }

    static {
        final GlobTypeBuilder typeBuilder = GlobTypeBuilderFactory.create("HeaderType");
        SenderCompID = typeBuilder.declareStringField("SenderCompID",
                FixFieldType.create("SenderCompID"));
        TargetCompID = typeBuilder.declareStringField("TargetCompID", FixFieldType.create("TargetCompID"));
        MsgType = typeBuilder.declareStringField("MsgType", FixFieldType.create("MsgType"));
        TYPE = typeBuilder.build();
    }
}
