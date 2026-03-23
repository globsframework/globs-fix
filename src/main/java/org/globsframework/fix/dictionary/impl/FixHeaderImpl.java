package org.globsframework.fix.dictionary.impl;

import org.globsframework.fix.dictionary.FixElement;
import org.globsframework.fix.dictionary.FixHeader;

import java.util.ArrayList;
import java.util.List;

public class FixHeaderImpl implements FixHeader {
    private final List<FixElement> fields = new ArrayList<>();
    @Override
    public List<FixElement> getElements() {
        return fields;
    }

    public void add(FixElement fixElement) {
        fields.add(fixElement);
    }
}
