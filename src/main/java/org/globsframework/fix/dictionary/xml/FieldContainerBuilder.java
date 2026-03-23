package org.globsframework.fix.dictionary.xml;

public interface FieldContainerBuilder {
    FieldContainerBuilder addGroup(String name, boolean required);

    void addField(String name, boolean required);

    void addComponent(String name, boolean required);
}
