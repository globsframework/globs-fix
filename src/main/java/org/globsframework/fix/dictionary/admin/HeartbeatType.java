package org.globsframework.fix.dictionary.admin;

import org.globsframework.core.metamodel.GlobType;
import org.globsframework.core.metamodel.GlobTypeBuilder;
import org.globsframework.core.metamodel.GlobTypeBuilderFactory;
import org.globsframework.core.metamodel.fields.StringField;
import org.globsframework.core.model.Glob;
import org.globsframework.fix.dictionary.model.FixFieldType;
import org.globsframework.fix.dictionary.model.FixMessageType;

public class HeartbeatType {
    public static final GlobType TYPE;

    public static final StringField testReqID;

    static {
        GlobTypeBuilder builder = GlobTypeBuilderFactory.create("Heartbeat");
        builder.addAnnotation(FixMessageType.create("Heartbeat"));
        testReqID = builder.declareStringField("testReqID", FixFieldType.create("TestReqID"));
        TYPE = builder.build();
    }

    public static Glob create(String testReqId) {
        return TYPE.instantiate()
                .set(testReqID, testReqId);
    }

    public static Glob create() {
        return TYPE.instantiate();
    }
}
