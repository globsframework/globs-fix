package org.globsframework.fix.dictionary.admin;

import org.globsframework.core.metamodel.GlobType;
import org.globsframework.core.metamodel.GlobTypeBuilder;
import org.globsframework.core.metamodel.GlobTypeBuilderFactory;
import org.globsframework.core.metamodel.fields.IntegerField;
import org.globsframework.core.metamodel.fields.StringField;
import org.globsframework.core.model.MutableGlob;
import org.globsframework.fix.dictionary.model.FixFieldType;
import org.globsframework.fix.dictionary.model.FixMessageType;

public class RejectType {
    public static final GlobType TYPE;

    public static final IntegerField refSeqNum;
    public static final IntegerField refTagID;
    public static final StringField refMsgType;
    public static final IntegerField sessionRejectReason;
    public static final StringField text;
    public static final IntegerField encodedTextLen;
    public static final StringField encodedText;

    static {
        GlobTypeBuilder builder = GlobTypeBuilderFactory.create("Reject");
        builder.addAnnotation(FixMessageType.create("Reject"));
        refSeqNum = builder.declareIntegerField("refSeqNum", FixFieldType.create("RefSeqNum"));
        refTagID = builder.declareIntegerField("refTagID", FixFieldType.create("RefTagID"));
        refMsgType = builder.declareStringField("refMsgType", FixFieldType.create("RefMsgType"));
        sessionRejectReason = builder.declareIntegerField("sessionRejectReason", FixFieldType.create("SessionRejectReason"));
        text = builder.declareStringField("text", FixFieldType.create("Text"));
        encodedTextLen = builder.declareIntegerField("encodedTextLen", FixFieldType.create("EncodedTextLen"));
        encodedText = builder.declareStringField("encodedText", FixFieldType.create("EncodedText"));
        TYPE = builder.build();
    }

    public static MutableGlob create(int refSeqNum, String refMsgType, String raison) {
        return TYPE.instantiate()
                .set(RejectType.refSeqNum, refSeqNum)
                .set(RejectType.refMsgType, refMsgType)
                .set(RejectType.text, raison)
                ;
    }

    public static MutableGlob create(int refSeqNum, String refMsgType, int sessionRejectReason, String raison) {
        return create(refSeqNum, refMsgType, raison)
                .set(RejectType.sessionRejectReason, sessionRejectReason);
    }
}
