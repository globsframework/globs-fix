package org.globsframework.fix.dictionary.impl;

import org.globsframework.fix.dictionary.FixComponent;
import org.globsframework.fix.dictionary.FixElement;

import java.util.ArrayList;
import java.util.List;

public class FixComponentImpl implements FixComponent {
    private final List<FixElement> fields = new ArrayList<>();
    private final String name;

    public FixComponentImpl(String name) {
        this.name = name;
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
}
