package org.globsframework.fix.serializer;

import org.globsframework.core.metamodel.GlobModel;
import org.globsframework.core.metamodel.GlobType;
import org.globsframework.core.metamodel.fields.*;
import org.globsframework.core.metamodel.impl.DefaultGlobModel;
import org.globsframework.core.model.Glob;
import org.globsframework.fix.Utils;
import org.globsframework.fix.dictionary.*;
import org.globsframework.fix.dictionary.admin.FixAdminModel;
import org.globsframework.fix.dictionary.model.FixComponentType;
import org.globsframework.fix.dictionary.model.FixFieldType;
import org.globsframework.fix.dictionary.model.FixGroupType;
import org.globsframework.fix.dictionary.model.FixMessageType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.*;

public class FixWriterBuilder {
    private static final Logger log = LoggerFactory.getLogger(FixWriterBuilder.class);
    private static final Set<Integer> headerFieldToIgnore = Set.of(8, 9, 35);
    private static final Set<Integer> trailerFieldToIgnore = Set.of(10);
    private final Map<GlobType, FieldWrite> writerPerMessageType;
    private final Map<GlobType, byte[]> messageTypePerType;
    private final FixModel fixModel;

    public FixWriterBuilder(Map<GlobType, FieldWrite> writerPerMessageType, Map<GlobType, byte[]> messageTypePerType, FixModel fixModel) {
        this.writerPerMessageType = writerPerMessageType;
        this.messageTypePerType = messageTypePerType;
        this.fixModel = fixModel;
    }

    public static FixWriterBuilder create(FixModel fixModel, GlobModel appGlobModel,
                                          GlobType headerType, GlobType trailerType) {

        Map<GlobType, List<FieldWrite>> writerPerType = new HashMap<>();

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

        Map<GlobType, FieldWrite> writerPerMessageType = new HashMap<>();

        Map<GlobType, byte[]> messageTypePerType = new HashMap<>();
        for (FixMessage message : fixModel.getMessages()) {
            final List<FixElement> elements = message.getElements();
            final GlobType messageType = messageTypeMap.get(message.getName());
            if (messageType == null) {
                log.debug("Glob type type not found for message: " + message.getName());
            } else {
                final List<FieldWrite> orCreate = getOrCreate(writerPerType, elements, messageType);
                writerPerMessageType.put(messageType, new MessageFieldWrite(orCreate));
                messageTypePerType.put(messageType, message.getMsgType().getBytes(StandardCharsets.US_ASCII));
            }
        }
        writerPerMessageType.put(headerType,
                new MessageFieldWrite(getOrCreate(writerPerType, fixModel.getHeader()
                        .getElements()
                        .stream()
                        .filter(fixElement -> !(fixElement instanceof FixField) ||
                                              !headerFieldToIgnore.contains(((FixField) fixElement).getId()))
                        .toList(), headerType)));

        writerPerMessageType.put(trailerType,
                new MessageFieldWrite(getOrCreate(writerPerType, fixModel.getTrailer()
                        .getElements()
                        .stream()
                        .filter(fixElement -> !(fixElement instanceof FixField) ||
                                              !trailerFieldToIgnore.contains(((FixField) fixElement).getId()))
                        .toList(), trailerType)));
        return new FixWriterBuilder(writerPerMessageType, messageTypePerType, fixModel);
    }

    private static List<FieldWrite> getOrCreate(Map<GlobType, List<FieldWrite>> types, List<FixElement> elements, GlobType messageType) {
        final List<FieldWrite> fieldWrites = types.get(messageType);
        if (fieldWrites != null) {
            return fieldWrites;
        }
        final List<FieldWrite> extracted = extracted(types, elements, messageType);
        types.put(messageType, extracted);
        return extracted;
    }

    private static List<FieldWrite> extracted(Map<GlobType, List<FieldWrite>> types, List<FixElement> elements, GlobType messageType) {
        final List<FieldWrite> fieldWrites = new ArrayList<>();
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
                case GlobField gl -> gl.getTargetType().findOptAnnotation(FixComponentType.UNIQUE_KEY)
                        .map(FixComponentType.name)
                        .ifPresent(s -> {
                            if (fields.put(s, field) != null) {
                                throw new RuntimeException("Duplicate component name " + s + " on " + field.getFullName());
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
        for (FixElement element : elements) {
            switch (element) {
                case FixField fixField -> {
                    final Field field = fields.get(fixField.getName());
                    if (field != null) {
                        switch (field) {
                            case StringField stringField -> fieldWrites.add(new StringFieldWrite(
                                    fixField.getId(),
                                    messageType.getGetAccessor(stringField)
                            ));
                            case IntegerField integerField -> fieldWrites.add(new IntegerFieldWrite(
                                    fixField.getId(),
                                    messageType.getGetAccessor(integerField)
                            ));
                            case BooleanField booleanField -> fieldWrites.add(new BooleanFieldWrite(
                                    fixField.getId(),
                                    messageType.getGetAccessor(booleanField)
                            ));
                            default ->
                                    throw new RuntimeException("Type " + field.getDataType() + " not managed on " + field.getFullName());
                        }
                    } else {
                        log.debug("No field for " + fixField.getName() + " (" + fixField.getId() + ")");
                    }
                }
                case FixComponent fixComponent -> {
                    final Field field = fields.get(fixComponent.getName());
                    if (field != null) {
                        if (field instanceof GlobField globField) {
                            final List<FieldWrite> writes = getOrCreate(types, fixComponent.getElements(), globField.getTargetType());
                            fieldWrites.add(new ComponentFieldWrite(globField, writes));
                        } else {
                            throw new RuntimeException("Field " + fixComponent.getName() + " is of type " + field.getDataType() +
                                                       "  but should be a Glob field " + field.getFullName());
                        }
                    } else {
                        log.debug("No glob field for " + fixComponent.getName());
                    }
                }
                case FixGroup fixGroup -> {
                    final String firstFieldName = fixGroup.getCountField().getName();
                    final Field field = fields.get(firstFieldName);
                    if (field != null) {
                        if (field instanceof GlobArrayField globArrayField) {
                            final List<FieldWrite> writes = getOrCreate(types, fixGroup.getElements(), globArrayField.getTargetType());
                            final byte[] idBytes = Integer.toString(fixGroup.getCountField().getId()).getBytes(StandardCharsets.US_ASCII);
                            fieldWrites.add(new GroupFieldWrite(globArrayField, idBytes, writes));
                        } else {
                            throw new RuntimeException("Field " + firstFieldName + " is of type " + field.getDataType() +
                                                       "  but should be a Glob Array Field " + field.getFullName());
                        }
                    } else {
                        log.debug("No glob array field for " + firstFieldName);
                    }

                }
            }
        }
        return fieldWrites;
    }

    public FixWriter createWriter(FixWriterImpl.Publish publish) {
        return new FixWriterImpl(fixModel, publish, writerPerMessageType, messageTypePerType);
    }

    private static class ComponentFieldWrite implements FieldWrite {
        private final GlobField globField;
        private final List<FieldWrite> writes;

        public ComponentFieldWrite(GlobField globField, List<FieldWrite> writes) {
            this.globField = globField;
            this.writes = writes;
        }

        @Override
        public int writeAt(byte[] buffer, int at, Glob data) {
            final Glob glob = data.get(globField);
            if (glob != null) {
                for (FieldWrite write : writes) {
                    at = write.writeAt(buffer, at, glob);
                }
            }
            return at;
        }
    }

    private static class GroupFieldWrite implements FieldWrite {
        private final GlobArrayField globArrayField;
        private final byte[] idBytes;
        private final List<FieldWrite> writes;

        public GroupFieldWrite(GlobArrayField globArrayField, byte[] idBytes, List<FieldWrite> writes) {
            this.globArrayField = globArrayField;
            this.idBytes = idBytes;
            this.writes = writes;
        }

        @Override
        public int writeAt(byte[] buffer, int at, Glob data) {
            final Glob[] globs = data.getOrEmpty(globArrayField);
            if (globs != null) {
                at = Utils.fastCopy(buffer, at, idBytes);
                buffer[at++] = '=';
                at = Utils.fastCopy(buffer, at, globs.length);
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

    private static class MessageFieldWrite implements FieldWrite {
        private final List<FieldWrite> writes;

        public MessageFieldWrite(List<FieldWrite> writes) {
            this.writes = writes;
        }

        @Override
        public int writeAt(byte[] buffer, int at, Glob data) {
            for (FieldWrite fieldWrite : writes) {
                at = fieldWrite.writeAt(buffer, at, data);
            }
            return at;
        }
    }
}
