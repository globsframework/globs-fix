package org.globsframework.fix;

import org.globsframework.core.metamodel.GlobType;
import org.globsframework.core.metamodel.GlobTypeBuilder;
import org.globsframework.core.metamodel.GlobTypeBuilderFactory;
import org.globsframework.core.metamodel.fields.BooleanField;
import org.globsframework.core.metamodel.fields.DateTimeField;
import org.globsframework.core.metamodel.fields.IntegerField;
import org.globsframework.core.metamodel.fields.StringField;
import org.globsframework.core.model.MutableGlob;
import org.globsframework.fix.dictionary.model.FixFieldType;
import org.globsframework.fix.engine.HeaderDesc;

public class HeaderType {
    public static final GlobType TYPE;

    public static final StringField senderCompID;

    public static final StringField targetCompID;

    public static final StringField msgType;

    public static final IntegerField msgSeqNum;

    public static final StringField sendingTime;

    public static final StringField origSendingTime;

    public static final BooleanField possDupFlag;

    public static MutableGlob create(String senderCompID, String targetCompID) {
        return TYPE.instantiate()
                .set(HeaderType.senderCompID, senderCompID)
                .set(HeaderType.targetCompID, targetCompID);
    }

    static {
        final GlobTypeBuilder typeBuilder = GlobTypeBuilderFactory.create("HeaderType");
        senderCompID = typeBuilder.declareStringField("senderCompID",
                FixFieldType.create("SenderCompID"));
        targetCompID = typeBuilder.declareStringField("targetCompID", FixFieldType.create("TargetCompID"));
        msgType = typeBuilder.declareStringField("msgType", FixFieldType.create("MsgType"));
        msgSeqNum = typeBuilder.declareIntegerField("msgSeqNum", FixFieldType.create("MsgSeqNum"));
        sendingTime = typeBuilder.declareStringField("sendingTime", FixFieldType.create("SendingTime"));
        origSendingTime = typeBuilder.declareStringField("origSendingTime", FixFieldType.create("OrigSendingTime"));
        possDupFlag = typeBuilder.declareBooleanField("possDupFlag", FixFieldType.create("PossDupFlag"));
        TYPE = typeBuilder.build();
    }

    public static HeaderDesc getHeaderDesc() {
        return new HeaderDesc(TYPE, senderCompID, targetCompID,
                msgType, msgSeqNum, possDupFlag, sendingTime, origSendingTime);
    }
}
