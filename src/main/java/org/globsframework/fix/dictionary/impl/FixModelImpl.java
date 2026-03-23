package org.globsframework.fix.dictionary.impl;

import org.globsframework.fix.dictionary.*;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

public class FixModelImpl implements FixModel {
    private final String version;
    private final FixHeaderImpl fixHeader;
    private final FixTrailerImpl fixTrailer;
    private final Map<String, FixMessage> messages;
    private final Map<String, FixComponent> components;
    private final Map<String, FixField> fields;

    public FixModelImpl(String version, FixHeaderImpl fixHeader, FixTrailerImpl fixTrailer, Map<String, FixMessage> messages,
                        Map<String, FixComponent> components, Map<String, FixField> fields) {
        this.version = version;
        this.fixHeader = fixHeader;
        this.fixTrailer = fixTrailer;
        this.messages = new HashMap<>(messages);
        this.components = new HashMap<>(components);
        this.fields = new HashMap<>(fields);
    }

    @Override
    public String getVersion() {
        return version;
    }

    @Override
    public FixHeader getHeader() {
        return fixHeader;
    }

    @Override
    public FixMessage getMessage(String messageName) {
        return messages.get(messageName);
    }

    @Override
    public FixComponent getComponent(String componentName) {
        return components.get(componentName);
    }

    @Override
    public Collection<FixMessage> getMessages() {
        return messages.values();
    }
}
