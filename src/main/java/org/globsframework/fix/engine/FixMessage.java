package org.globsframework.fix.engine;

import org.globsframework.core.metamodel.fields.*;
import org.globsframework.core.model.MutableGlob;

import java.time.ZonedDateTime;

public interface FixMessage {

    MutableGlob getHeader();

    MutableGlob getTrailer();

    MutableGlob getBody();

    boolean resetSeqNum();

    void update(StringField field, String value);

    void update(StringField field, char value);

    void update(BooleanField field, boolean value);

    void update(IntegerField field, int value);

    void update(DoubleField field, double value);

    void update(DateTimeField field, ZonedDateTime value);

    UpdatedField getUpdatedFields();

    record UpdatedField(short[] indexFields, int len) {
    }
}
