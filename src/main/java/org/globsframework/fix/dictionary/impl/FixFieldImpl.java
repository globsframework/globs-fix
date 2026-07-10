package org.globsframework.fix.dictionary.impl;

import org.globsframework.fix.dictionary.FixField;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

public class FixFieldImpl implements FixField {
    private static final Logger log = LoggerFactory.getLogger(FixFieldImpl.class);
    private final String name;
    private final int number;
    private final String type;
    private final boolean allowOtherValues;
    private int maxEnumSize = -1;
    private final Map<String, String> enums = new HashMap<>();

    public FixFieldImpl(String name, int number, String type, boolean allowOtherValues) {
        this.name = name;
        this.number = number;
        this.type = type;
        this.allowOtherValues = allowOtherValues;
    }

    public void addEnum(String value, String description) {
        if (enums.put(value, description) != null) {
            log.error("Duplicate enum: " + value + " for '" + name + "', id: " + number);
        }
        maxEnumSize = Math.max(maxEnumSize, value.length());
    }

    public int getMaxEnumLength(){
        return maxEnumSize;
    }

    @Override
    public String getType() {
        return type;
    }

    public Map<String, String> enums(){
        return enums;
    }

    public int getId() {
        return number;
    }

    public String getName() {
        return name;
    }
}
