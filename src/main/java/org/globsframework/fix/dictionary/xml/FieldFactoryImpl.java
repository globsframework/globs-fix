package org.globsframework.fix.dictionary.xml;

import org.globsframework.fix.dictionary.*;
import org.globsframework.fix.dictionary.impl.*;

import java.util.HashMap;
import java.util.Map;

public class FieldFactoryImpl implements FieldFactory {
    private final Map<String, FixField> fields = new HashMap<>();

    @Override
    public FieldBuilder newField(int number, String name, String type, boolean allowOtherValues) {
        final FixFieldImpl fixField = new FixFieldImpl(name, number, type, allowOtherValues);
        fields.put(name, fixField);
        return new FieldBuilder() {
            @Override
            public void addEnum(String value, String description) {
                fixField.addEnum(value, description);
            }
        };
    }

    @Override
    public ComponentBuilder complete() {
        return new ComponentBuilder() {
            Map<String, FixComponent> components = new HashMap<>();
            Map<String, FixComponentImpl> wanted = new HashMap<>();

            @Override
            public FieldContainerBuilder declare(String name) {
                final FixComponentImpl fixComponent;
                if (wanted.containsKey(name)) {
                    fixComponent = wanted.remove(name);
                }
                else {
                    fixComponent = new FixComponentImpl(name);
                }
                if (components.put(name, fixComponent) != null) {
                    throw new RuntimeException("Duplicate component: " + name);
                }
                return new FieldContainerBuilderImpl(fields, components, fixComponent::add){
                    @Override
                    public void addComponent(String name, boolean required) {
                        if (wanted.get(name) == null) {
                            final FixComponentImpl component = new FixComponentImpl(name);
                            wanted.put(name, component);
                            fixComponent.add(component);
                        } else {
                            super.addComponent(name, required);
                        }
                    }
                };
            }

            @Override
            public MessagesBuilder complete() {
                Map<String, FixMessage> messages = new HashMap<>();
                if (!wanted.isEmpty()) {
                    throw new RuntimeException("All messages not declared " + wanted.keySet());
                }
                return new MessagesBuilder() {
                    @Override
                    public FieldContainerBuilder declare(String name, String msgtype, String msgcat) {
                        final FixMessageImpl fixMessage = new FixMessageImpl(name, msgtype, msgcat);
                        if (messages.put(name, fixMessage) != null) {
                            throw new RuntimeException("Duplicate message: " + name);
                        }
                        return new FieldContainerBuilderImpl(fields, components, fixMessage::add);
                    }

                    @Override
                    public MainFIXBuilder complete() {
                        return new MainFIXBuilder() {
                            FixHeaderImpl fixHeader = new FixHeaderImpl();
                            FixTrailerImpl fixTrailer = new FixTrailerImpl();

                            @Override
                            public FieldContainerBuilder declareHeader() {
                                return new FieldContainerBuilderImpl(fields, components, fixHeader::add);
                            }

                            @Override
                            public FieldContainerBuilder declareTrailer() {
                                return new FieldContainerBuilderImpl(fields, components, fixTrailer::add);
                            }

                            @Override
                            public FixModel complete(String version) {
                                try {
                                    return new FixModelImpl(version, fixHeader, fixTrailer, messages, components, fields);
                                } finally {
                                    fields.clear();
                                    components.clear();
                                    messages.clear();
                                }
                            }
                        };
                    }
                };
            }
        };
    }

    private static class FieldContainerBuilderImpl implements FieldContainerBuilder {
        private final Map<String, FixField> fields;
        private final Map<String, FixComponent> components;
        private final AddElement fixComponent;

        interface AddElement {
            void add(FixElement fixElement);
        }

        public FieldContainerBuilderImpl(Map<String, FixField> fields, Map<String, FixComponent> components, AddElement fixComponent) {
            this.fields = fields;
            this.components = components;
            this.fixComponent = fixComponent;
        }

        @Override
        public FieldContainerBuilder addGroup(String name, boolean required) {
            final FixGroupImpl fixElement = new FixGroupImpl(fields.get(name), required);
            fixComponent.add(fixElement);
            return new FieldContainerBuilderImpl(fields, components, fixElement::add);
        }

        @Override
        public void addField(String name, boolean required) {
            final FixField fixElement = fields.get(name);
            if (fixElement == null) {
                throw new RuntimeException("Field " + name + " has not declared");
            }
            fixComponent.add(fixElement);
        }

        @Override
        public void addComponent(String name, boolean required) {
            final FixComponent fixElement = components.get(name);
            if (fixElement == null) {
                throw new RuntimeException("component " + name + " has not declared");
            }
            fixComponent.add(fixElement);
        }
    }
}
