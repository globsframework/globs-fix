package org.globsframework.fix.dictionary.admin;

import org.globsframework.core.metamodel.GlobType;
import org.globsframework.core.metamodel.GlobTypeBuilder;
import org.globsframework.core.metamodel.GlobTypeBuilderFactory;
import org.globsframework.core.metamodel.fields.BooleanField;
import org.globsframework.core.metamodel.fields.IntegerField;
import org.globsframework.core.model.Glob;
import org.globsframework.core.model.MutableGlob;
import org.globsframework.fix.dictionary.model.FixFieldType;
import org.globsframework.fix.dictionary.model.FixMessageType;

public class SequenceResetType {
    public static final GlobType TYPE;

    public static final BooleanField gapFillFlag;
    public static final IntegerField newSeqNo;

    public static MutableGlob create(boolean gapFillFlag, int newSeqNo) {
        return TYPE.instantiate()
                .set(SequenceResetType.gapFillFlag,gapFillFlag)
                .set(SequenceResetType.newSeqNo, newSeqNo);
    }

    static {
        GlobTypeBuilder builder = GlobTypeBuilderFactory.create("SequenceReset");
        builder.addAnnotation(FixMessageType.create("SequenceReset"));
        gapFillFlag = builder.declareBooleanField("gapFillFlag", FixFieldType.create("GapFillFlag"));
        newSeqNo = builder.declareIntegerField("newSeqNo", FixFieldType.create("NewSeqNo"));
        TYPE = builder.build();
    }
}
