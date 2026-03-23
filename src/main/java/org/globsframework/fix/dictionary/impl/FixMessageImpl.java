package org.globsframework.fix.dictionary.impl;

import org.globsframework.fix.dictionary.FixElement;
import org.globsframework.fix.dictionary.FixMessage;

import java.util.ArrayList;
import java.util.List;

public class FixMessageImpl implements FixMessage {
    private final List<FixElement> fields = new ArrayList<>();
    private final String name;
    private final String msgtype;
    private final String msgcat;

    public FixMessageImpl(String name, String msgtype, String msgcat) {
        this.name = name;
        this.msgtype = msgtype;
        this.msgcat = msgcat;
    }

    @Override
    public List<FixElement> getElements() {
        return fields;
    }

    public void add(FixElement fixElement) {
        fields.add(fixElement);
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getMsgType() {
        return msgtype;
    }
}
