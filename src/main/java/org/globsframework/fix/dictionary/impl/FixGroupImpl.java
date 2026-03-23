package org.globsframework.fix.dictionary.impl;

import org.globsframework.fix.dictionary.FixElement;
import org.globsframework.fix.dictionary.FixField;
import org.globsframework.fix.dictionary.FixGroup;

import java.util.ArrayList;
import java.util.List;

public class FixGroupImpl implements FixGroup {
    private final List<FixElement> fields = new ArrayList<>();
    private final FixField countField;
    private final boolean required;

    public FixGroupImpl(FixField countField, boolean required) {
        this.countField = countField;
        this.required = required;
    }

    @Override
    public List<FixElement> getElements() {
        return fields;
    }

    public void add(FixElement fixElement) {
        fields.add(fixElement);
    }

    @Override
    public FixField getCountField() {
        return countField;
    }
}
