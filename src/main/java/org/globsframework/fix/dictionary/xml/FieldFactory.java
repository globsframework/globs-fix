package org.globsframework.fix.dictionary.xml;

public interface FieldFactory {
    FieldBuilder newField(int number, String name, String type, boolean allowOtherValues);

    ComponentBuilder complete();
}
