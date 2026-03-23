package org.globsframework.fix.dictionary.xml;

import org.globsframework.fix.dictionary.FixModel;
import org.globsframework.saxstack.parser.*;
import org.globsframework.saxstack.utils.XmlUtils;
import org.xml.sax.Attributes;

import java.io.IOException;
import java.io.Reader;
import java.util.function.Supplier;

public class ReadFixDictionary {

    public static FixModel parse(String version, Supplier<Reader> reader, FieldFactory fieldFactory) throws IOException {
        SaxStackParser.parse(XmlUtils.getXmlReader(), new RootFieldXmlNode(fieldFactory), reader.get());
        final ComponentBuilder componentBuilder = fieldFactory.complete();
        SaxStackParser.parse(XmlUtils.getXmlReader(), new RootComponentXmlNode(componentBuilder), reader.get());
        final MessagesBuilder messagesBuilder = componentBuilder.complete();
        SaxStackParser.parse(XmlUtils.getXmlReader(), new RootMessageXmlNode(messagesBuilder), reader.get());
        final MainFIXBuilder mainFIXBuilder = messagesBuilder.complete();
        SaxStackParser.parse(XmlUtils.getXmlReader(), new RootFixModelXmlNode(mainFIXBuilder), reader.get());
        return mainFIXBuilder.complete(version);
    }

    public static class RootFixModelXmlNode extends DefaultXmlNode {
        private final MainFIXBuilder mainFIXBuilder;

        public RootFixModelXmlNode(MainFIXBuilder mainFIXBuilder) {
            this.mainFIXBuilder = mainFIXBuilder;
        }

        @Override
        public XmlNode getSubNode(String childName, Attributes xmlAttrs, String uri, String fullName) {
            if (childName.equals("fix")) {
                return this;
            }
            if (childName.equals("header")) {
                return new ContainmentXmlNode(mainFIXBuilder.declareHeader());
            }
            if (childName.equals("trailer")) {
                return new ContainmentXmlNode(mainFIXBuilder.declareTrailer());
            }
            return SilentXmlNode.INSTANCE;
        }
    }

    public static class RootMessageXmlNode extends DefaultXmlNode {
        private final MessagesBuilder messagesBuilder;

        public RootMessageXmlNode(MessagesBuilder messagesBuilder) {
            this.messagesBuilder = messagesBuilder;
        }

        @Override
        public XmlNode getSubNode(String childName, Attributes xmlAttrs, String uri, String fullName) {
            if (childName.equals("fix")) {
                return this;
            }
            if (childName.equals("messages")) {
                return new MessageXmlNode(messagesBuilder);
            }
            return  SilentXmlNode.INSTANCE;
        }

        public static class MessageXmlNode  extends DefaultXmlNode {
            private final MessagesBuilder messagesBuilder;

            public MessageXmlNode(MessagesBuilder messagesBuilder) {
                this.messagesBuilder = messagesBuilder;
            }

            @Override
            public XmlNode getSubNode(String childName, Attributes xmlAttrs, String uri, String fullName) {
                if (childName.equals("message")) {
                    return new ReadFixDictionary.ContainmentXmlNode(
                            messagesBuilder.declare(
                                    xmlAttrs.getValue("name"),
                                    xmlAttrs.getValue("msgtype"),
                                    xmlAttrs.getValue("msgcat")));
                }
                return SilentXmlNode.INSTANCE;
            }
        }
    }

    public static class RootComponentXmlNode extends DefaultXmlNode {
        final ComponentBuilder componentBuilder;

        public RootComponentXmlNode(ComponentBuilder componentBuilder) {
            this.componentBuilder = componentBuilder;
        }

        @Override
        public XmlNode getSubNode(String childName, Attributes xmlAttrs, String uri, String fullName) {
            if (childName.equals("fix")) {
                return this;
            }
            if (childName.equals("components")) {
                return new ComponentXmlNode(componentBuilder);
            }
            return SilentXmlNode.INSTANCE;
        }
    }

    public static class ComponentXmlNode  extends DefaultXmlNode {
        private final ComponentBuilder componentBuilder;

        public ComponentXmlNode(ComponentBuilder componentBuilder) {
            this.componentBuilder = componentBuilder;
        }

        @Override
        public XmlNode getSubNode(String childName, Attributes xmlAttrs, String uri, String fullName) {
            if (childName.equals("component")) {
                return new ReadFixDictionary.ContainmentXmlNode(
                        componentBuilder.declare(xmlAttrs.getValue("name")));
            }
            return SilentXmlNode.INSTANCE;
        }

    }

    public static class RootFieldXmlNode implements XmlNode {
        private final FieldFactory fieldFactory;

        public RootFieldXmlNode(FieldFactory fieldFactory) {
            this.fieldFactory = fieldFactory;
        }

        @Override
        public XmlNode getSubNode(String childName, Attributes xmlAttrs, String uri, String fullName) throws ExceptionHolder {
            if (childName.equals("fix")) {
                return this;
            }
            if (childName.equals("fields")) {
                return new FieldXmlNode(fieldFactory);
            }
            return SilentXmlNode.INSTANCE;
        }

        @Override
        public void setValue(String value) throws ExceptionHolder {
        }

        @Override
        public void complete() throws ExceptionHolder {
        }

        static class FieldXmlNode extends DefaultXmlNode {
            private final FieldFactory fieldFactory;
            public FieldXmlNode(FieldFactory fieldFactory) {
                this.fieldFactory = fieldFactory;
            }

            @Override
            public XmlNode getSubNode(String childName, Attributes xmlAttrs, String uri, String fullName) {
                if (childName.equals("field")) {
                    final int number = Integer.parseInt(xmlAttrs.getValue("number"));
                    final String name = xmlAttrs.getValue("name");
                    final String type = xmlAttrs.getValue("type");
                    boolean allowOtherValues = Boolean.parseBoolean(xmlAttrs.getValue("allowOtherValues"));
                    final FieldBuilder fieldBuilder = fieldFactory.newField(number, name, type, allowOtherValues);
                    return new FieldEnumXmlNode(fieldBuilder);
                }
                return SilentXmlNode.INSTANCE;
            }
        }


        static class FieldEnumXmlNode extends DefaultXmlNode {
            private final FieldBuilder fieldBuilder;
            public FieldEnumXmlNode(FieldBuilder fieldBuilder) {
                this.fieldBuilder = fieldBuilder;
            }

            @Override
            public XmlNode getSubNode(String childName, Attributes xmlAttrs, String uri, String fullName) {
                if (childName.equals("value")) {
                    final String enumValue = xmlAttrs.getValue("enum");
                    final String description = xmlAttrs.getValue("description");
                    fieldBuilder.addEnum(enumValue, description);
                }
                return super.getSubNode(childName, xmlAttrs, uri, fullName);
            }
        }
    }

    private static class ContainmentXmlNode extends DefaultXmlNode {
        private final FieldContainerBuilder fieldContainerBuilder;

        public ContainmentXmlNode(FieldContainerBuilder fieldContainerBuilder) {
            this.fieldContainerBuilder = fieldContainerBuilder;
        }

        @Override
        public XmlNode getSubNode(String childName, Attributes xmlAttrs, String uri, String fullName) {
            if (childName.equals("field")) {
                fieldContainerBuilder.addField(xmlAttrs.getValue("name"), isRequired(xmlAttrs));
                return SilentXmlNode.INSTANCE;
            }
            if  (childName.equals("group")) {
                return new ContainmentXmlNode(
                        fieldContainerBuilder.addGroup(xmlAttrs.getValue("name"), isRequired(xmlAttrs)));
            }
            if (childName.equals("component")) {
                fieldContainerBuilder.addComponent(xmlAttrs.getValue("name"), isRequired(xmlAttrs));
                return SilentXmlNode.INSTANCE;
            }
            return SilentXmlNode.INSTANCE;
        }

        private static boolean isRequired(Attributes xmlAttrs) {
            return "Y".equalsIgnoreCase(xmlAttrs.getValue("required"));
        }
    }
}
