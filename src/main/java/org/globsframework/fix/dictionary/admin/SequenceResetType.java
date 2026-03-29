package org.globsframework.fix.dictionary.admin;

import org.globsframework.core.metamodel.GlobType;
import org.globsframework.core.metamodel.GlobTypeBuilder;
import org.globsframework.core.metamodel.GlobTypeBuilderFactory;
import org.globsframework.core.metamodel.fields.BooleanField;
import org.globsframework.core.metamodel.fields.IntegerField;
import org.globsframework.fix.dictionary.model.FixFieldType;
import org.globsframework.fix.dictionary.model.FixMessageType;

public class SequenceResetType {
    public static final GlobType TYPE;

    public static final BooleanField gapFillFlag;
    public static final IntegerField newSeqNo;

    static {
        GlobTypeBuilder builder = GlobTypeBuilderFactory.create("SequenceReset");
        builder.addAnnotation(FixMessageType.create("SequenceReset"));
        gapFillFlag = builder.declareBooleanField("gapFillFlag", FixFieldType.create("GapFillFlag"));
        newSeqNo = builder.declareIntegerField("newSeqNo", FixFieldType.create("NewSeqNo"));
        TYPE = builder.build();
    }
}
