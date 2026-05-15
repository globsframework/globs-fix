package org.globsframework.fix.engine;

import org.globsframework.core.metamodel.GlobType;
import org.globsframework.core.metamodel.fields.*;
import org.globsframework.core.model.MutableGlob;

import java.time.ZonedDateTime;

public class FixMessageImpl implements FixMessage {
    private final MutableGlob header;
    private final MutableGlob data;
    private final MutableGlob trailer;
    private final boolean resetSeqNum;
    private final short[] updatedFields;
    private int count;

    private FixMessageImpl(MutableGlob header, MutableGlob data, MutableGlob trailer, boolean resetSeqNum, boolean allField) {
        this.header = header;
        this.data = data;
        this.trailer = trailer;
        this.resetSeqNum = resetSeqNum;
        if (!allField) {
            updatedFields = new short[data.getType().getFieldCount()];
        }
        else {
            updatedFields = null;
        }
    }


    public static FixMessage fromType(MutableGlob header, GlobType type, MutableGlob trailer) {
        return new FixMessageImpl(header, type.instantiate(), trailer, false, false);
    }

    public static FixMessage fromGlob(MutableGlob header, MutableGlob message, MutableGlob trailer, boolean resetSeqNum) {
        return new FixMessageImpl(header, message, trailer, resetSeqNum, true);
    }

    @Override
    public MutableGlob getHeader() {
        return header;
    }

    @Override
    public MutableGlob getTrailer() {
        return trailer;
    }

    @Override
    public MutableGlob getBody() {
        return data;
    }

    @Override
    public boolean resetSeqNum() {
        return resetSeqNum;
    }

    @Override
    public void update(StringField field, String value) {
        if (updatedFields != null && data.isNotSet(field)) {
            updatedFields[count++] = (short) field.getIndex();
        }
        data.set(field, value);
    }

    @Override
    public void update(StringField field, char value) {
        if (updatedFields != null && data.isNotSet(field)) {
            updatedFields[count++] = (short) field.getIndex();
        }
        data.set(field, "" + value);
    }

    @Override
    public void update(BooleanField field, boolean value) {
        if (updatedFields != null && data.isNotSet(field)) {
            updatedFields[count++] = (short) field.getIndex();
        }
        data.set(field, value);
    }

    @Override
    public void update(IntegerField field, int value) {
        if (updatedFields != null && data.isNotSet(field)) {
            updatedFields[count++] = (short) field.getIndex();
        }
        data.set(field, value);
    }

    @Override
    public void update(DoubleField field, double value) {
        if (updatedFields != null && data.isNotSet(field)) {
            updatedFields[count++] = (short) field.getIndex();
        }
        data.set(field, value);
    }

    @Override
    public void update(DateTimeField field, ZonedDateTime value) {
        if (updatedFields != null && data.isNotSet(field)) {
            updatedFields[count++] = (short) field.getIndex();
        }
        data.set(field, value);
    }

    @Override
    public UpdatedField getUpdatedFields() {
        if (updatedFields != null) {
            return new UpdatedField(updatedFields, count);
        }
        return null;
    }
}
