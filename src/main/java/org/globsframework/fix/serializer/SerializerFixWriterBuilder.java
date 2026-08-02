package org.globsframework.fix.serializer;

import org.globsframework.core.metamodel.GlobModel;
import org.globsframework.core.metamodel.GlobType;
import org.globsframework.core.metamodel.fields.*;
import org.globsframework.core.metamodel.impl.DefaultGlobModel;
import org.globsframework.core.model.Glob;
import org.globsframework.core.model.globaccessor.get.GlobGetBytesAccessor;
import org.globsframework.core.model.globaccessor.get.GlobGetStringAccessor;
import org.globsframework.fix.FormatDateTime;
import org.globsframework.fix.Utils;
import org.globsframework.fix.dictionary.*;
import org.globsframework.fix.dictionary.admin.FixAdminModel;
import org.globsframework.fix.dictionary.model.FixFieldType;
import org.globsframework.fix.dictionary.model.FixGroupType;
import org.globsframework.fix.dictionary.model.FixMessageType;
import org.globsframework.fix.dictionary.reverter.FixModelToGlobType;
import org.globsframework.fix.engine.HeaderDesc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.*;

public class SerializerFixWriterBuilder implements FixWriterBuilder {
    private static final Logger log = LoggerFactory.getLogger(SerializerFixWriterBuilder.class);
    private static final FieldWrite WRITE_NOTHING = (buffer, at, data) -> at;
    private static final Set<Integer> headerFieldToIgnore = Set.of(8, 9, 35);
    private static final Set<Integer> trailerFieldToIgnore = Set.of(10);
    private final Map<GlobType, MessageFieldWrite> writerPerMessageType;
    private final Map<GlobType, byte[]> messageTypePerType;
    private final FixModel fixModel;
    private final IntegerField checksum;
    private final HeaderDesc headerDesc;
    private final FormatDateTime utcFormater;

    public SerializerFixWriterBuilder(Map<GlobType, MessageFieldWrite> writerPerMessageType, Map<GlobType, byte[]> messageTypePerType,
                                      FixModel fixModel, GlobType trailerType, HeaderDesc headerDesc, FormatDateTime utcFormater) {
        this.writerPerMessageType = writerPerMessageType;
        this.messageTypePerType = messageTypePerType;
        this.fixModel = fixModel;
        checksum = HeaderDesc.getField(trailerType, "CheckSum").map(Field::asIntegerField).orElse(null);
        this.headerDesc = headerDesc;
        this.utcFormater = utcFormater;
    }

    public static SerializerFixWriterBuilder create(FixModel fixModel, GlobModel appGlobModel,
                                                    GlobType headerType, GlobType trailerType, FormatDateTime utcFormater) {

        HeaderDesc headerDesc = HeaderDesc.create(headerType);
        DefaultGlobModel globModel = new DefaultGlobModel(appGlobModel);
        FixAdminModel.MODEL.getAll().forEach(globModel::add);

        Map<String, GlobType> messageTypeMap = new HashMap<>();
        final Collection<GlobType> all = globModel.getAll();
        for (GlobType globType : all) {
            final Glob messageType = globType.findAnnotation(FixMessageType.UNIQUE_KEY);
            if (messageType != null) {
                GlobType tmp;
                if ((tmp = messageTypeMap.put(messageType.get(FixMessageType.name), globType)) != null) {
                    log.error("Duplicate message type name: " + messageType.get(FixMessageType.name) + " for "
                    + globType.getName() + " vs " + tmp.getName());
                }
            }
        }

        Map<GlobType, MessageFieldWrite> writerPerMessageType = new HashMap<>();

        Map<GlobType, byte[]> messageTypePerType = new HashMap<>();
        for (FixMessageDescriptor message : fixModel.getMessages()) {
            final List<FixElement> elements = message.getElements();
            final GlobType messageType = messageTypeMap.get(message.getName());
            if (messageType == null) {
                log.debug("Glob type type not found for message: " + message.getName());
            } else {
                final HashMap<Field, FieldWrite> fieldWrites = new HashMap<>();
                extracted(fieldWrites, elements, messageType);
                writerPerMessageType.put(messageType, new MessageFieldWrite(messageType, fieldWrites));
                messageTypePerType.put(messageType, message.getMsgType().getBytes(StandardCharsets.ISO_8859_1));
            }
        }
        writerPerMessageType.put(headerType,
                new MessageFieldWrite(headerType, extracted(new HashMap<>(), fixModel.getHeader()
                        .getElements()
                        .stream()
                        .filter(fixElement -> !(fixElement instanceof FixField) ||
                                              !headerFieldToIgnore.contains(((FixField) fixElement).getId()))
                        .toList(), headerType)));

        writerPerMessageType.put(trailerType,
                new MessageFieldWrite(trailerType, extracted(new HashMap<>(), fixModel.getTrailer()
                        .getElements()
                        .stream()
                        .filter(fixElement -> !(fixElement instanceof FixField) ||
                                              !trailerFieldToIgnore.contains(((FixField) fixElement).getId()))
                        .toList(), trailerType)));
        return new SerializerFixWriterBuilder(writerPerMessageType, messageTypePerType, fixModel,
                trailerType, headerDesc, utcFormater);
    }

    private static Map<Field, FieldWrite> extracted(Map<Field, FieldWrite> fieldWrites, List<FixElement> elements, GlobType messageType) {
        Map<String, Field> fields = new HashMap<>();
        for (Field field : messageType.getFields()) {
            switch (field) {
                case GlobArrayField gal -> gal.getTargetType().findOptAnnotation(FixGroupType.UNIQUE_KEY)
                        .map(FixGroupType.name)
                        .ifPresent(s -> {
                            if (fields.put(s, field) != null) {
                                throw new RuntimeException("Duplicate group name " + s + " on " + field.getFullName());
                            }
                        });
                default -> field.findOptAnnotation(FixFieldType.UNIQUE_KEY)
                        .map(FixFieldType.name)
                        .ifPresent(s -> {
                            if (fields.put(s, field) != null) {
                                throw new RuntimeException("Duplicate field name " + s + " on " + field.getFullName());
                            }
                        });
            }


        }
        // a DATA field is always preceded, in the same container, by the LENGTH field giving its size
        FixField previousFixField = null;
        for (FixElement element : elements) {
            switch (element) {
                case FixField fixField -> {
                    final Field field = fields.get(fixField.getName());
                    if (FixModelToGlobType.DATA.equals(fixField.getType())) {
                        declareDataField(fieldWrites, fields, previousFixField, fixField, field, messageType);
                    } else if (field != null) {
                        switch (field) {
                            case StringField stringField -> fieldWrites.put(field,
                                    StringFieldWrite.create(fixField, fixField.getId(), messageType.getGetAccessor(stringField)
                            ));
                            case IntegerField integerField -> fieldWrites.put(field, new IntegerFieldWrite(
                                    fixField.getId(),
                                    messageType.getGetAccessor(integerField)
                            ));
                            case BooleanField booleanField -> fieldWrites.put(field, new BooleanFieldWrite(
                                    fixField.getId(),
                                    messageType.getGetAccessor(booleanField)
                            ));
                            case DateTimeField dateTimeField -> fieldWrites.put(field, new DateTimeFieldWrite(
                                    fixField.getId(),
                                    messageType.getGetAccessor(dateTimeField)
                            ));
                            case StringArrayField stringArrayField ->
                                    fieldWrites.put(field, new MultipleValueStringFieldWrite(
                                            fixField.getId(),
                                            messageType.getGetAccessor(stringArrayField)
                                    ));
                            default ->
                                    throw new RuntimeException("Type " + field.getDataType() + " not managed on " + field.getFullName());
                        }
                    } else {
                        log.debug("No field for " + fixField.getName() + " (" + fixField.getId() + ")");
                    }
                    previousFixField = fixField;
                }
                case FixComponent fixComponent -> {
                    extracted(fieldWrites, fixComponent.getElements(), messageType);
                    previousFixField = null;
                }
                case FixGroup fixGroup -> {
                    final String firstFieldName = fixGroup.getCountField().getName();
                    final Field field = fields.get(firstFieldName);
                    if (field != null) {
                        if (field instanceof GlobArrayField globArrayField) {
                            final Map<Field, FieldWrite> writes = extracted(new HashMap<>(), fixGroup.getElements(), globArrayField.getTargetType());
                            final byte[] idBytes = Integer.toString(fixGroup.getCountField().getId()).getBytes(StandardCharsets.ISO_8859_1);
                            fieldWrites.put(field, new GroupFieldWrite(globArrayField, idBytes, writes));
                        } else {
                            throw new RuntimeException("Field " + firstFieldName + " is of type " + field.getDataType() +
                                                       "  but should be a Glob Array Field " + field.getFullName());
                        }
                    } else {
                        log.debug("No glob array field for " + firstFieldName);
                    }
                    previousFixField = null;
                }
            }
        }
        return fieldWrites;
    }

    /*
    The LENGTH field that precedes a DATA field is written by the data writer, from the payload : it
    must not write anything of its own, and nothing at all is written when the data is not bound.
     */
    private static void declareDataField(Map<Field, FieldWrite> fieldWrites, Map<String, Field> fields,
                                         FixField lengthFixField, FixField dataFixField, Field dataField,
                                         GlobType messageType) {
        if (lengthFixField == null || !FixModelToGlobType.LENGTH.equals(lengthFixField.getType())) {
            throw new RuntimeException("Data field " + dataFixField.getName() + " is not preceded by a length field");
        }
        final Field boundLength = fields.get(lengthFixField.getName());
        if (boundLength != null) {
            fieldWrites.put(boundLength, WRITE_NOTHING);
        }
        if (dataField == null) {
            log.debug("No field for " + dataFixField.getName() + " (" + dataFixField.getId() + ")");
            return;
        }
        fieldWrites.put(dataField, switch (dataField) {
            case BytesField bytesField -> {
                final GlobGetBytesAccessor accessor = messageType.getGetAccessor(bytesField);
                yield DataFieldWrite.create(lengthFixField.getId(), dataFixField.getId(), accessor);
            }
            case StringField stringField -> {
                final GlobGetStringAccessor accessor = messageType.getGetAccessor(stringField);
                yield DataFieldWrite.create(lengthFixField.getId(), dataFixField.getId(), accessor);
            }
            default -> throw new RuntimeException("Type " + dataField.getDataType() + " not managed on the data field "
                                                  + dataField.getFullName());
        });
    }

    @Override
    public FixWriter createWriter(Publish publish, MsgSeqProvider msgSeqProvider) {
        return new FixWriterImpl(fixModel, publish, writerPerMessageType, messageTypePerType,
                msgSeqProvider, checksum, headerDesc, utcFormater);
    }

    private final static class GroupFieldWrite implements FieldWrite {
        private final GlobArrayField globArrayField;
        private final byte[] idBytes;
        private final FieldWrite[] writes;

        public GroupFieldWrite(GlobArrayField globArrayField, byte[] idBytes, Map<Field, FieldWrite> writes) {
            this.globArrayField = globArrayField;
            this.idBytes = idBytes;
            this.writes = writes.values().toArray(new FieldWrite[0]);
        }

        @Override
        public int writeAt(byte[] buffer, int at, Glob data) {
            final Glob[] globs = data.getOrEmpty(globArrayField);
            if (globs != null) {
                at = Utils.transfert(buffer, at, idBytes);
                buffer[at++] = '=';
                at = Utils.transfertInt(buffer, at, globs.length);
                buffer[at++] = 0x1;
                for (Glob glob : globs) {
                    for (FieldWrite write : writes) {
                        at = write.writeAt(buffer, at, glob);
                    }
                }
            }
            return at;
        }
    }
}
