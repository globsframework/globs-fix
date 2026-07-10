package org.globsframework.fix.dictionary;

import java.util.Map;

public non-sealed interface FixField extends FixElement {
    int getMaxEnumLength();

    String getType();

    Map<String, String> enums();

    int getId();

    String getName();
}
