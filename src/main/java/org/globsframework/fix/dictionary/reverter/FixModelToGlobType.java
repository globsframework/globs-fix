package org.globsframework.fix.dictionary.reverter;

import org.globsframework.core.metamodel.GlobType;
import org.globsframework.core.metamodel.GlobTypeBuilder;
import org.globsframework.core.metamodel.impl.DefaultGlobTypeBuilder;
import org.globsframework.fix.dictionary.*;
import org.globsframework.fix.dictionary.model.FixFieldType;
import org.globsframework.fix.dictionary.model.FixGroupType;
import org.globsframework.fix.dictionary.model.FixMessageType;

import java.util.ArrayList;
import java.util.List;


public class FixModelToGlobType {

    public static final String INT = "INT";
    public static final String NUMINGROUP = "NUMINGROUP";
    public static final String BOOLEAN = "BOOLEAN";
    public static final String FLOAT = "FLOAT";

    static public FixGlobType toType(FixModel fixModel) {

        List<GlobType> messageTypes = new ArrayList<>();
        for (FixMessageDescriptor fixMessageDescriptor : fixModel.getMessages()) {
            GlobTypeBuilder messageBuilder = DefaultGlobTypeBuilder.init(fixMessageDescriptor.getName());
            messageBuilder.addAnnotation(FixMessageType.create(fixMessageDescriptor.getMsgType()));
            createGlobType(fixMessageDescriptor.getElements(), messageBuilder);
            messageTypes.add(messageBuilder.build());
        }

        GlobTypeBuilder headerBuilder = DefaultGlobTypeBuilder.init("header");
        createGlobType(fixModel.getHeader().getElements(), headerBuilder);

        GlobTypeBuilder trailerBuilder = DefaultGlobTypeBuilder.init("trailer");
        createGlobType(fixModel.getTrailer().getElements(), trailerBuilder);

        return new FixGlobType(headerBuilder.build(), trailerBuilder.build(), messageTypes.toArray(GlobType[]::new));
    }

    private static void createGlobType(List<FixElement> elements, GlobTypeBuilder typeBuilder) {
        for (FixElement element : elements) {
            switch (element) {
                case FixComponent fixComponent -> {
                    createGlobType(fixComponent.getElements(), typeBuilder);
                }
                case FixField fixField -> {
                    final String type = fixField.getType();
                    switch (type) {
                        case INT, NUMINGROUP -> typeBuilder.declareIntegerField(fixField.getName(),
                                FixFieldType.create(fixField.getName()));
                        case BOOLEAN -> typeBuilder.declareBooleanField(fixField.getName(),
                                FixFieldType.create(fixField.getName()));
                        case FLOAT -> typeBuilder.declareDoubleField(fixField.getName(),
                                FixFieldType.create(fixField.getName()));
                        default -> typeBuilder.declareStringField(fixField.getName(),
                                FixFieldType.create(fixField.getName()));
                    }
                }
                case FixGroup fixGroup -> {
                    final String name = fixGroup.getCountField().getName();
                    GlobTypeBuilder group = new DefaultGlobTypeBuilder(name);
                    group.addAnnotation(FixGroupType.create(name));
                    createGlobType(fixGroup.getElements(), group);
                    typeBuilder.declareGlobArrayField(name, group::build);
                }
            }
        }
    }

    public record FixGlobType(GlobType header, GlobType footer, GlobType[] messages) {
    }

}
