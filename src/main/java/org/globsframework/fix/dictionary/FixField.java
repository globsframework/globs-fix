package org.globsframework.fix.dictionary;

import java.util.Map;

public non-sealed interface FixField extends FixElement {
    int getMaxEnumLenght();

    Map<String, String> enums();

    int getId();

    String getName();
}
