package org.globsframework.fix.dictionary;

import java.util.Collection;

public interface FixModel {

    String getVersion();

    FixTrailer getTrailer();

    FixHeader getHeader();

    FixMessageDescriptor getMessage(String messageName);

    FixComponent getComponent(String componentName);

    Collection<FixMessageDescriptor> getMessages();
}
