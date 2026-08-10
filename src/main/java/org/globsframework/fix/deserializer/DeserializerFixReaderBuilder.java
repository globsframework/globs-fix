package org.globsframework.fix.deserializer;

import org.globsframework.core.metamodel.GlobModel;
import org.globsframework.core.metamodel.GlobType;
import org.globsframework.core.metamodel.fields.*;
import org.globsframework.core.metamodel.impl.DefaultGlobModel;
import org.globsframework.core.model.Glob;
import org.globsframework.core.utils.collections.IntHashMap;
import org.globsframework.fix.dictionary.*;
import org.globsframework.fix.dictionary.admin.FixAdminModel;
import org.globsframework.fix.dictionary.model.FixGroupType;
import org.globsframework.fix.dictionary.model.FixMessageType;
import org.globsframework.fix.dictionary.reverter.FixModelToGlobType;

import java.util.*;

public class DeserializerFixReaderBuilder implements FixReaderBuilder {
    private final Map<String, FixMessageStructure> messageFixStruct;
    private final FixStruct fixHeader;
    private final FixStruct fixTrailer;
    private final FixModel fixModel;

    private DeserializerFixReaderBuilder(Map<String, FixMessageStructure> messageFixStruct, FixStruct fixHeader, FixStruct fixTrailer, FixModel fixModel) {
        this.messageFixStruct = messageFixStruct;
        this.fixHeader = fixHeader;
        this.fixTrailer = fixTrailer;
        this.fixModel = fixModel;
    }


    public static DeserializerFixReaderBuilder create(FixModel fixModel, GlobModel appGlobModel, GlobType headerType, GlobType trailerType) {
        FixFieldAccessor fixFieldAccessor = new FixFieldAccessor();
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
        Map<String, FixMessageStructure> messageFixStruct = new HashMap<>();
        final Collection<FixMessageDescriptor> messages = fixModel.getMessages();
        for (FixMessageDescriptor message : messages) {
            final String name = message.getName();
            final GlobType type = messageTypeMap.get(name);
            messageFixStruct.put(message.getMsgType(),
                    new FixMessageStructure(message.getMsgType(), type,
                            computeFixStruct(type, fixModel.getMessage(name), fixFieldAccessor)));
        }

        final FixHeader header = fixModel.getHeader();
        final FixTrailer trailer = fixModel.getTrailer();
        final FixStruct fixHeader = computeFixStruct(headerType, header, fixFieldAccessor);
        final FixStruct fixTrailer = computeFixStruct(trailerType, trailer, fixFieldAccessor);
        return new DeserializerFixReaderBuilder(messageFixStruct, fixHeader, fixTrailer, fixModel);
    }

    private static FixStruct computeFixStruct(GlobType type, FixElementContainer message, FixFieldAccessor fixFieldAccessor) {
        return new FixStructImpl(type, getFieldReaderIntHashMap(type, message, fixFieldAccessor, new IntHashMap<>()));
    }

    private static IntHashMap<FieldReader> getFieldReaderIntHashMap(GlobType type, FixElementContainer message,
                                                                    FixFieldAccessor fixFieldAccessor, IntHashMap<FieldReader> fieldReaders) {
        final Map<String, Field> fixFieldAccessorFixField = fixFieldAccessor.getFixField(type);
        final List<FixElement> elements = message.getElements();
        // a DATA field is always preceded, in the same container, by the LENGTH field giving its size
        FixField previousFixField = null;
        for (FixElement element : elements) {
            switch (element) {
                case FixComponent component -> {
                    getFieldReaderIntHashMap(type, component, fixFieldAccessor, fieldReaders);
                    previousFixField = null;
                }
                case FixField fixField -> {
                    final Field field = fixFieldAccessorFixField.get(fixField.getName());
                    if (FixModelToGlobType.DATA.equals(fixField.getType())) {
                        declareDataField(fieldReaders, fixFieldAccessorFixField, previousFixField, fixField, field);
                    }
                    else if (field == null) {
                        fieldReaders.put(fixField.getId(), new NoFieldDirectFieldReader());
                    }
                    else {
                        switch (field) {
                            case StringField stringField ->
                                    fieldReaders.put(fixField.getId(), new StringFieldDirectFieldReader(stringField));
                            case StringArrayField stringField ->
                                    fieldReaders.put(fixField.getId(), new MultipleValueStringFieldDirectFieldReader(stringField));
                            case IntegerField integerField ->
                                    fieldReaders.put(fixField.getId(), new IntFieldDirectFieldReader(integerField));
                            case BooleanField booleanField ->
                                    fieldReaders.put(fixField.getId(), new BooleanFieldDirectFieldReader(booleanField));
                            case DateTimeField dateTimeField ->
                                    fieldReaders.put(fixField.getId(), new DateTimeFieldDirectFieldReader(dateTimeField));
                            default ->
                                    throw new RuntimeException("Unsupported field type: " + field.getDataType() + " for " + field.getFullName());
                        }
                    }
                    previousFixField = fixField;
                }
                case FixGroup fixGroup -> {
                    final FixField countField = fixGroup.getCountField();
                    boolean found = false;
                    if (type != null) {
                        for (Field field : type.getFields()) {
                            if (field instanceof GlobArrayField<?> globArrayField) {
                                if (globArrayField.getTargetType().findOptAnnotation(FixGroupType.UNIQUE_KEY)
                                        .map(FixGroupType.name)
                                        .filter(id -> Objects.equals(id, countField.getName())).isPresent()) {
                                    if (found) {
                                        throw new RuntimeException("Duplicate count field found for group: "
                                                                   + countField.getName());
                                    }
                                    found = true;
                                    final FixStruct fixStruct = computeFixStruct(
                                            globArrayField.getTargetType(), fixGroup, fixFieldAccessor);
                                    fieldReaders.put(countField.getId(), new FieldGroupReader(globArrayField, fixStruct));
                                }
                            }
                        }
                    }
                    if (!found) {
                        final FixStruct fixStruct = computeFixStruct(null, fixGroup, fixFieldAccessor);
                        FieldReader groupFieldReader = new NoFieldGroupReader(fixStruct);
                        fieldReaders.put(countField.getId(), groupFieldReader);
                    }
                    previousFixField = null;
                }
            }
        }
        return fieldReaders;
    }

    /*
    A DATA field and the LENGTH field that precedes it are read as a pair : the length drives the
    parsing, so it gets a reader of its own even when the GlobType binds neither of them.
     */
    private static void declareDataField(IntHashMap<FieldReader> fieldReaders, Map<String, Field> boundFields,
                                         FixField lengthFixField, FixField dataFixField, Field dataField) {
        if (lengthFixField == null || !FixModelToGlobType.LENGTH.equals(lengthFixField.getType())) {
            throw new RuntimeException("Data field " + dataFixField.getName() + " is not preceded by a length field");
        }
        final Field boundLength = boundFields.get(lengthFixField.getName());
        fieldReaders.put(lengthFixField.getId(), boundLength instanceof IntegerField integerField
                ? new IntFieldDataLengthReader(integerField, dataFixField.getId())
                : new NoFieldDataLengthReader(dataFixField.getId()));
        fieldReaders.put(dataFixField.getId(), switch (dataField) {
            case null -> new NoFieldDataReader();
            case BytesField bytesField -> new BytesFieldDataReader(bytesField);
            case StringField stringField -> new StringFieldDataReader(stringField);
            default -> throw new RuntimeException("Unsupported field type: " + dataField.getDataType() +
                                                  " for the data field " + dataField.getFullName());
        });
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
