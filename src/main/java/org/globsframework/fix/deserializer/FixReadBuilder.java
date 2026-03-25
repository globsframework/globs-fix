package org.globsframework.fix.deserializer;

import org.globsframework.core.metamodel.GlobModel;
import org.globsframework.core.metamodel.GlobType;
import org.globsframework.core.metamodel.fields.*;
import org.globsframework.core.model.Glob;
import org.globsframework.core.model.MutableGlob;
import org.globsframework.core.utils.collections.IntHashMap;
import org.globsframework.fix.dictionary.*;
import org.globsframework.fix.dictionary.model.FixComponentType;
import org.globsframework.fix.dictionary.model.FixFieldType;
import org.globsframework.fix.dictionary.model.FixGroupType;
import org.globsframework.fix.dictionary.model.FixMessageType;

import java.util.*;

public class FixReadBuilder {
    private final Map<String, FixStruct> messageFixStruct;
    private final FixStruct fixHeader;
    private final FixModel fixModel;

    private FixReadBuilder(Map<String, FixStruct> messageFixStruct, FixStruct fixHeader, FixModel fixModel) {
        this.messageFixStruct = messageFixStruct;
        this.fixHeader = fixHeader;
        this.fixModel = fixModel;
    }

    public static FixReadBuilder create(FixModel fixModel, GlobModel globModel, GlobType headerType) {
        Map<String, GlobType> messageTypeMap = new HashMap<>();
        final Collection<GlobType> all = globModel.getAll();
        for (GlobType globType : all) {
            final Glob messageType = globType.findAnnotation(FixMessageType.UNIQUE_KEY);
            if (messageType != null) {
                messageTypeMap.put(messageType.get(FixMessageType.name), globType);
            }
        }
        Map<String, FixStruct> messageFixStruct = new HashMap<>();
        Map<String, FixStruct> namedFixStruct = new HashMap<>();
        final Collection<FixMessage> messages = fixModel.getMessages();
        for (FixMessage message : messages) {
            final String name = message.getName();
            messageFixStruct.put(message.getMsgType(), computeFixStruct(namedFixStruct,
                    messageTypeMap.get(name), fixModel.getMessage(name)));
        }

        final FixHeader header = fixModel.getHeader();
        final FixStruct fixHeader = computeFixStruct(namedFixStruct, headerType, header);
        return new FixReadBuilder(messageFixStruct, fixHeader, fixModel);
    }

    private static FixStruct computeFixStruct(Map<String, FixStruct> namedFixStruct, GlobType type, FixElementContainer message) {
        IntHashMap<FieldReader> fieldReaders = new IntHashMap<>();
        final List<FixElement> elements = message.getElements();
        for (FixElement element : elements) {
            switch (element) {
                case FixComponent component -> {
                    boolean found = false;
                    if (type != null) {
                        for (Field field : type.getFields()) {
                            if (field instanceof GlobField globField) {
                                if (globField.getTargetType().findOptAnnotation(FixComponentType.UNIQUE_KEY)
                                        .map(FixComponentType.name)
                                        .filter(n -> Objects.equals(n, component.getName()))
                                        .isPresent()) {
                                    if (found) {
                                        throw new IllegalStateException("Multiple component declared " + component.getName() +
                                                                        " on " + globField.getFullName());
                                    }
                                    if (!namedFixStruct.containsKey(component.getName())) {
                                        final FixStruct fixStruct = computeFixStruct(namedFixStruct,
                                                globField.getTargetType(), component);
                                        namedFixStruct.put(component.getName(), fixStruct);
                                    }
                                    final FixStruct fixStruct = namedFixStruct.get(component.getName());
                                    FieldReader reader = new ComponentReaderImpl(fixStruct, globField);
                                    allSubFor(fieldReaders, component, reader);
                                    found = true;
                                }
                            }
                        }
                    }
                    if (!found) {
                        FixStruct fixStruct = namedFixStruct.get(component.getName());
                        if (fixStruct == null) {
                            fixStruct = computeFixStruct(namedFixStruct, null, component);
                        }
                        FieldReader reader = new NoFieldComponentReaderImpl(fixStruct);
                        allSubFor(fieldReaders, component, reader);
                    }
                }
                case FixField fixField -> {
                    boolean found = false;
                    if (type != null) {
                        for (Field field : type.getFields()) {
                            if (field.findOptAnnotation(FixFieldType.UNIQUE_KEY)
                                    .map(FixFieldType.name)
                                    .filter(name -> name.equals(fixField.getName())).isPresent()) {
                                if (found) {
                                    throw new RuntimeException("Duplicate unique key field: " + fixField.getName());
                                }
                                switch (field) {
                                    case StringField stringField ->
                                            fieldReaders.put(fixField.getId(), new StringFieldDirectFieldReader(stringField));
                                    case IntegerField integerField ->
                                            fieldReaders.put(fixField.getId(), new IntFieldDirectFieldReader(integerField));
                                    default ->
                                            throw new RuntimeException("Unsupported field type: " + field.getDataType() + " for " + field.getFullName());
                                }
                                found = true;
                            }
                        }
                    }
                    if (!found) {
                        fieldReaders.put(fixField.getId(), new NoFieldDirectFieldReader());
                    }
                }
                case FixGroup fixGroup -> {
                    final FixField countField = fixGroup.getCountField();
                    boolean found = false;
                    if (type != null) {
                        for (Field field : type.getFields()) {
                            if (field instanceof GlobArrayField globArrayField) {
                                if (globArrayField.getTargetType().findOptAnnotation(FixGroupType.UNIQUE_KEY)
                                        .map(FixGroupType.name)
                                        .filter(id -> Objects.equals(id, countField.getName())).isPresent()) {
                                    if (found) {
                                        throw new RuntimeException("Duplicate count field found for group: "
                                                                   + countField.getName());
                                    }
                                    found = true;
                                    final FixStruct fixStruct = computeFixStruct(namedFixStruct,
                                            globArrayField.getTargetType(), fixGroup);
                                    fieldReaders.put(countField.getId(), new FieldGroupReader(globArrayField, fixStruct));
                                }
                            }
                        }
                    }
                    if (!found) {
                        final FixStruct fixStruct = computeFixStruct(namedFixStruct, null, fixGroup);
                        FieldReader groupFieldReader = new NoFieldGroupReader(fixStruct);
                        fieldReaders.put(countField.getId(), groupFieldReader);
                    }
                }
            }
        }
        return new FixStructImpl(type, fieldReaders);
    }

    private static void allSubFor(IntHashMap<FieldReader> fieldReaders, FixElementContainer component, FieldReader fieldReader) {
        final List<FixElement> elements = component.getElements();
        for (FixElement element : elements) {
            if (element instanceof FixField fixField) {
                fieldReaders.put(fixField.getId(), fieldReader);
            } else if (element instanceof FixGroup fixGroup) {
                fieldReaders.put(fixGroup.getCountField().getId(), fieldReader);
                allSubFor(fieldReaders, fixGroup, fieldReader);
            } else if (element instanceof FixComponent fixComponent) {
                allSubFor(fieldReaders, fixComponent, fieldReader);
            }
        }
    }

    public FixReader createReader(ByteReader reader) {
        return new FixReaderImpl(reader, messageFixStruct, fixHeader, fixModel, (byte) 0x1);
    }

    public FixReader createReader(ByteReader reader, byte sep) {
        return new FixReaderImpl(reader, messageFixStruct, fixHeader, fixModel, sep);
    }

    private static class StringFieldDirectFieldReader implements DirectFieldReader {
        private final StringField field;

        public StringFieldDirectFieldReader(StringField field) {
            this.field = field;
        }

        @Override
        public void read(int from, int to, byte[] buffer, MutableGlob data) {
            data.set(field, new String(buffer, from, to - from));
        }

        @Override
        public boolean isSet(MutableGlob data) {
            return data.isSet(field);
        }
    }

    private static class NoFieldDirectFieldReader implements DirectFieldReader {
        @Override
        public void read(int from, int to, byte[] buffer, MutableGlob data) {
        }

        @Override
        public boolean isSet(MutableGlob data) {
            return false;
        }
    }

    private static class IntFieldDirectFieldReader implements DirectFieldReader {
        private final IntegerField integerField;

        public IntFieldDirectFieldReader(IntegerField integerField) {
            this.integerField = integerField;
        }

        @Override
        public boolean isSet(MutableGlob data) {
            return data.isSet(integerField);
        }

        @Override
        public void read(int from, int to, byte[] buffer, MutableGlob data) {
            data.set(integerField, FixReaderImpl.getIntAt(from, to, buffer));
        }
    }

    private static class ComponentReaderImpl implements ComponentReader {
        private final FixStruct fixStruct;
        private final GlobField globField;

        public ComponentReaderImpl(FixStruct fixStruct, GlobField globField) {
            this.fixStruct = fixStruct;
            this.globField = globField;
        }

        @Override
        public FixStruct getComponent() {
            return fixStruct;
        }

        @Override
        public void update(Glob glob, MutableGlob data) {
            data.set(globField, glob);
        }

        @Override
        public boolean isSet(MutableGlob data) {
            return data.isSet(globField);
        }
    }

    private static class NoFieldComponentReaderImpl implements ComponentReader {
        private final FixStruct fixStruct;

        public NoFieldComponentReaderImpl(FixStruct fixStruct) {
            this.fixStruct = fixStruct;
        }

        @Override
        public FixStruct getComponent() {
            return fixStruct;
        }

        @Override
        public void update(Glob glob, MutableGlob data) {
        }

        @Override
        public boolean isSet(MutableGlob data) {
            return false;
        }
    }

    private static class FieldGroupReader implements GroupReader {
        private final GlobArrayField globArrayField;
        private final FixStruct fixStruct;

        public FieldGroupReader(GlobArrayField globArrayField, FixStruct fixStruct) {
            this.globArrayField = globArrayField;
            this.fixStruct = fixStruct;
        }

        @Override
        public FixStruct sub() {
            return fixStruct;
        }

        @Override
        public void update(Glob[] group, MutableGlob data) {
            data.set(globArrayField, group);
        }

        @Override
        public boolean isSet(MutableGlob data) {
            return data.isSet(globArrayField);
        }
    }

    private static class NoFieldGroupReader implements GroupReader {
        private final FixStruct fixStruct;

        public NoFieldGroupReader(FixStruct fixStruct) {
            this.fixStruct = fixStruct;
        }

        @Override
        public FixStruct sub() {
            return fixStruct;
        }

        @Override
        public void update(Glob[] group, MutableGlob data) {
        }

        @Override
        public boolean isSet(MutableGlob data) {
            return false;
        }
    }
}
