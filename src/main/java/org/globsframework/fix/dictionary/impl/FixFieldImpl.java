package org.globsframework.fix.dictionary.impl;

import org.globsframework.fix.dictionary.FixField;

import java.util.HashMap;
import java.util.Map;

public class FixFieldImpl implements FixField {
    private final String name;
    private final int number;
    private final String type;
    private final boolean allowOtherValues;
    private final Map<String, String> enums = new HashMap<>();

    public FixFieldImpl(String name, int number, String type, boolean allowOtherValues) {
        this.name = name;
        this.number = number;
        this.type = type;
        this.allowOtherValues = allowOtherValues;
    }

    public void addEnum(String value, String description) {
        if (enums.put(value, description) != null) {
            throw new IllegalArgumentException("Duplicate enum: " + value + " for '" + name + "', id: " + number);
        }
    }

    @Override
    public int getId() {
        return number;
    }

    @Override
    public String getName() {
        return name;
    }
}
