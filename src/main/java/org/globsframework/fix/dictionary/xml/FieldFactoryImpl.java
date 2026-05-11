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
            FieldContainerBuilderImpl.GetComponent getComponent = new FieldContainerBuilderImpl.GetComponent() {
                @Override
                public FixComponent get(String name) {
                    final FixComponent fixComponent = components.get(name);
                    if (fixComponent != null) {
                        return fixComponent;
                    }
                    final FixComponentImpl fixComponent1 = wanted.get(name);
                    if (fixComponent1 != null) {
                        return fixComponent1;
                    }
                    final FixComponentImpl fixComponent2 = new FixComponentImpl(name);
                    wanted.put(name, fixComponent2);
                    return fixComponent2;
                }
            };
            final Map<String, FixComponent> components = new HashMap<>();
            final Map<String, FixComponentImpl> wanted = new HashMap<>();

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
                return new FieldContainerBuilderImpl(fields, getComponent, fixComponent::add);
            }

            @Override
            public MessagesBuilder complete() {
                Map<String, FixMessageDescriptor> messages = new HashMap<>();
                if (!wanted.isEmpty()) {
                    throw new RuntimeException("All messages not declared " + wanted.keySet());
                }
                return new MessagesBuilder() {
                    @Override
                    public FieldContainerBuilder declare(String name, String msgtype, String msgcat) {
                        final FixMessageDescriptorImpl fixMessage = new FixMessageDescriptorImpl(name, msgtype, msgcat);
                        if (messages.put(name, fixMessage) != null) {
                            throw new RuntimeException("Duplicate message: " + name);
                        }
                        return new FieldContainerBuilderImpl(fields, getComponent, fixMessage::add);
                    }

                    @Override
                    public MainFIXBuilder complete() {
                        return new MainFIXBuilder() {
                            final FixHeaderImpl fixHeader = new FixHeaderImpl();
                            final FixTrailerImpl fixTrailer = new FixTrailerImpl();

                            @Override
                            public FieldContainerBuilder declareHeader() {
                                return new FieldContainerBuilderImpl(fields, getComponent, fixHeader::add);
                            }

                            @Override
                            public FieldContainerBuilder declareTrailer() {
                                return new FieldContainerBuilderImpl(fields, getComponent, fixTrailer::add);
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
        private final GetComponent components;
        private final AddElement fixComponent;

        interface AddElement {
            void add(FixElement fixElement);
        }

        interface GetComponent {
            FixComponent get(String name);
        }

        public FieldContainerBuilderImpl(Map<String, FixField> fields, GetComponent components, AddElement fixComponent) {
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
