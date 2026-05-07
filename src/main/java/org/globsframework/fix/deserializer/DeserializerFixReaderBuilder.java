package org.globsframework.fix.deserializer;

import org.globsframework.core.metamodel.GlobModel;
import org.globsframework.core.metamodel.GlobType;
import org.globsframework.core.metamodel.fields.*;
import org.globsframework.core.metamodel.impl.DefaultGlobModel;
import org.globsframework.core.model.Glob;
import org.globsframework.core.utils.collections.IntHashMap;
import org.globsframework.fix.dictionary.*;
import org.globsframework.fix.dictionary.admin.FixAdminModel;
import org.globsframework.fix.dictionary.model.FixComponentType;
import org.globsframework.fix.dictionary.model.FixFieldType;
import org.globsframework.fix.dictionary.model.FixGroupType;
import org.globsframework.fix.dictionary.model.FixMessageType;

import java.util.*;

public class DeserializerFixReaderBuilder implements FixReaderBuilder {
    private final Map<String, FixStruct> messageFixStruct;
    private final FixStruct fixHeader;
    private final FixStruct fixTrailer;
    private final FixModel fixModel;

    private DeserializerFixReaderBuilder(Map<String, FixStruct> messageFixStruct, FixStruct fixHeader, FixStruct fixTrailer, FixModel fixModel) {
        this.messageFixStruct = messageFixStruct;
        this.fixHeader = fixHeader;
        this.fixTrailer = fixTrailer;
        this.fixModel = fixModel;
    }

    public static DeserializerFixReaderBuilder create(FixModel fixModel, GlobModel appGlobModel, GlobType headerType, GlobType trailerType) {

        DefaultGlobModel globModel = new DefaultGlobModel(appGlobModel);
        FixAdminModel.MODEL.getAll().forEach(globModel::add);

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
        final FixTrailer trailer = fixModel.getTrailer();
        final FixStruct fixHeader = computeFixStruct(namedFixStruct, headerType, header);
        final FixStruct fixTrailer = computeFixStruct(namedFixStruct, trailerType, trailer);
        return new DeserializerFixReaderBuilder(messageFixStruct, fixHeader, fixTrailer, fixModel);
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
                                    case BooleanField booleanField ->
                                            fieldReaders.put(fixField.getId(), new BooleanFieldDirectFieldReader(booleanField));
                                    case DateTimeField dateTimeField ->
                                            fieldReaders.put(fixField.getId(), new DateTimeFieldDirectFieldReader(dateTimeField));
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

    @Override
    public FixReader createReader(ByteReader reader) {
        return createReader(reader, null, 0);
    }

    @Override
    public FixReader createReader(ByteReader reader, byte[] initialBuffer, int len) {
        final FixReaderImpl fixReader = new FixReaderImpl(reader, messageFixStruct, fixHeader, fixTrailer, fixModel, (byte) 0x1);
        if (initialBuffer != null && len != 0) {
            fixReader.initBuffer(initialBuffer, len);
        }
        return fixReader;
    }

}
