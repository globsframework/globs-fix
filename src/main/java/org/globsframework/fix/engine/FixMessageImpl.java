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

    private FixMessageImpl(MutableGlob header, MutableGlob data, MutableGlob trailer, boolean resetSeqNum) {
        this.header = header;
        this.data = data;
        this.trailer = trailer;
        this.resetSeqNum = resetSeqNum;
    }

    public static FixMessage fromType(MutableGlob header, GlobType type, MutableGlob trailer) {
        return new FixMessageImpl(header, type.instantiate(), trailer, false);
    }

    public static FixMessage fromGlob(MutableGlob header, MutableGlob message, MutableGlob trailer, boolean resetSeqNum) {
        return new FixMessageImpl(header, message, trailer, resetSeqNum);
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
        data.set(field, value);
    }

    @Override
    public void update(StringField field, char value) {
        data.set(field, "" + value);
    }

    @Override
    public void update(BooleanField field, boolean value) {
        data.set(field, value);
    }

    @Override
    public void update(IntegerField field, int value) {
        data.set(field, value);
    }

    @Override
    public void update(DoubleField field, double value) {
        data.set(field, value);
    }

    @Override
    public void update(DateTimeField field, ZonedDateTime value) {
        data.set(field, value);
    }
}
