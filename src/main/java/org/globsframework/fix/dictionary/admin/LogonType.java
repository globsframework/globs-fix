package org.globsframework.fix.dictionary.admin;

import org.globsframework.core.metamodel.GlobType;
import org.globsframework.core.metamodel.GlobTypeBuilder;
import org.globsframework.core.metamodel.GlobTypeBuilderFactory;
import org.globsframework.core.metamodel.fields.BooleanField;
import org.globsframework.core.metamodel.fields.GlobArrayField;
import org.globsframework.core.metamodel.fields.IntegerField;
import org.globsframework.core.metamodel.fields.StringField;
import org.globsframework.core.model.Glob;
import org.globsframework.core.model.MutableGlob;
import org.globsframework.fix.dictionary.model.FixFieldType;
import org.globsframework.fix.dictionary.model.FixGroupType;
import org.globsframework.fix.dictionary.model.FixMessageType;

public class LogonType {
    public static final GlobType TYPE;

    public static final IntegerField encryptMethod;
    public static final IntegerField heartBtInt;
    public static final IntegerField rawDataLength;
    public static final StringField rawData;
    public static final BooleanField resetSeqNumFlag;
    public static final IntegerField nextExpectedMsgSeqNum;
    public static final IntegerField maxMessageSize;
    public static final GlobArrayField msgTypes;
    public static final BooleanField testMessageIndicator;
    public static final StringField username;
    public static final StringField password;

    public static Glob create(int heartbeatInS) {
        return TYPE.instantiate()
                .set(heartBtInt, heartbeatInS);
    }

    public static MutableGlob create(int encryp, Glob...msgTypes) {
        return TYPE.instantiate()
                .set(encryptMethod, encryp)
                .set(LogonType.msgTypes, msgTypes);
    }


    public static class NoMsgTypes {
        public static final GlobType TYPE;

        public static final StringField RefMsgType;
        public static final StringField MsgDirection;

        static {
            GlobTypeBuilder builder = GlobTypeBuilderFactory.create("NoMsgTypes");
            builder.addAnnotation(FixGroupType.create("NoMsgTypes"));
            RefMsgType = builder.declareStringField("RefMsgType", FixFieldType.create("RefMsgType"));
            MsgDirection = builder.declareStringField("MsgDirection", FixFieldType.create("MsgDirection"));
            TYPE = builder.build();
        }

        public static Glob create(String msgType, String direction) {
            return TYPE.instantiate()
                    .set(RefMsgType, msgType)
                    .set(MsgDirection, direction);
        }
    }

    static {
        GlobTypeBuilder builder = GlobTypeBuilderFactory.create("Logon");
        builder.addAnnotation(FixMessageType.create("Logon"));
        encryptMethod = builder.declareIntegerField("encryptMethod", FixFieldType.create("EncryptMethod"));
        heartBtInt = builder.declareIntegerField("heartBtInt", FixFieldType.create("HeartBtInt"));
        rawDataLength = builder.declareIntegerField("RawDataLength", FixFieldType.create("RawDataLength"));
        rawData = builder.declareStringField("rawData", FixFieldType.create("RawData"));
        resetSeqNumFlag = builder.declareBooleanField("resetSeqNumFlag", FixFieldType.create("ResetSeqNumFlag"));
        nextExpectedMsgSeqNum = builder.declareIntegerField("nextExpectedMsgSeqNum", FixFieldType.create("NextExpectedMsgSeqNum"));
        maxMessageSize = builder.declareIntegerField("maxMessageSize", FixFieldType.create("MaxMessageSize"));
        msgTypes = builder.declareGlobArrayField("msgTypes", () -> NoMsgTypes.TYPE);
        testMessageIndicator = builder.declareBooleanField("testMessageIndicator", FixFieldType.create("TestMessageIndicator"));
        username = builder.declareStringField("username", FixFieldType.create("Username"));
        password = builder.declareStringField("password", FixFieldType.create("Password"));
        TYPE = builder.build();
    }
}
