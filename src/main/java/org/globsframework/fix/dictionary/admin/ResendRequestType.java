package org.globsframework.fix.dictionary.admin;

import org.globsframework.core.metamodel.GlobType;
import org.globsframework.core.metamodel.GlobTypeBuilder;
import org.globsframework.core.metamodel.GlobTypeBuilderFactory;
import org.globsframework.core.metamodel.fields.IntegerField;
import org.globsframework.core.model.Glob;
import org.globsframework.core.model.MutableGlob;
import org.globsframework.fix.dictionary.model.FixFieldType;
import org.globsframework.fix.dictionary.model.FixMessageType;

public class ResendRequestType {
    public static final GlobType TYPE;

    public static final IntegerField beginSeqNo;
    public static final IntegerField endSeqNo;

    static {
        GlobTypeBuilder builder = GlobTypeBuilderFactory.create("ResendRequest");
        builder.addAnnotation(FixMessageType.create("ResendRequest"));
        beginSeqNo = builder.declareIntegerField("beginSeqNo", FixFieldType.create("BeginSeqNo"));
        endSeqNo = builder.declareIntegerField("endSeqNo", FixFieldType.create("EndSeqNo"));
        TYPE = builder.build();
    }

    public static MutableGlob create(int beginSeqNo, int endSeqNo) {
        return TYPE.instantiate()
                .set(ResendRequestType.beginSeqNo, beginSeqNo)
                .set(ResendRequestType.endSeqNo, endSeqNo);
    }
}
