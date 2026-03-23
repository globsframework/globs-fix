package org.globsframework.fix.dictionary.xml;

public interface ComponentBuilder {
    FieldContainerBuilder declare(String name);

    MessagesBuilder complete();
}
