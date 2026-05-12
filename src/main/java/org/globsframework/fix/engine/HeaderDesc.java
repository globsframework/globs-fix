package org.globsframework.fix.engine;

import org.globsframework.core.metamodel.GlobType;
import org.globsframework.core.metamodel.fields.*;
import org.globsframework.fix.dictionary.model.FixFieldType;

import java.util.Optional;

public record HeaderDesc(GlobType headerType, StringField senderCompIDField,
                         StringField targetCompIDField,
                         StringField msgType,
                         IntegerField seqNumField,
                         BooleanField isDup,
                         StringField sendingTime,
                         StringField origSendingTime) {

    public static HeaderDesc create(GlobType headerType) {
        StringField senderCompIDField = getField(headerType, "SenderCompID").map(Field::asStringField).orElseThrow();
        StringField targetCompIDField = getField(headerType, "TargetCompID").map(Field::asStringField).orElseThrow();
        StringField msgType = getField(headerType, "MsgType").map(Field::asStringField).orElseThrow();
        IntegerField seqNumField = getField(headerType, "MsgSeqNum").map(Field::asIntegerField).orElseThrow();
        BooleanField isDup = getField(headerType, "PossDupFlag").map(Field::asBooleanField).orElseThrow();
        StringField sendingTime = getField(headerType, "SendingTime").map(Field::asStringField).orElseThrow();
        StringField origSendingTime = getField(headerType, "OrigSendingTime").map(Field::asStringField).orElseThrow();
        return new HeaderDesc(headerType, senderCompIDField, targetCompIDField, msgType, seqNumField, isDup, sendingTime, origSendingTime);
    }

    public static Optional<Field> getField(GlobType trailerType, String fixName) {
        return trailerType.streamFields()
                .filter(field -> field.findOptAnnotation(FixFieldType.UNIQUE_KEY)
                        .map(FixFieldType.name)
                        .filter(name -> name.equals(fixName))
                        .isPresent()).findFirst();
    }


}
