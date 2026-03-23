package org.globsframework.fix.dictionary.xml;

public interface MessagesBuilder {
    FieldContainerBuilder declare(String name, String msgtype, String msgcat);

    MainFIXBuilder complete();
}
