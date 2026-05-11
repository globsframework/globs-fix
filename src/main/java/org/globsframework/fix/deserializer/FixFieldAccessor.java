package org.globsframework.fix.deserializer;

import org.globsframework.core.metamodel.GlobType;
import org.globsframework.core.metamodel.fields.Field;
import org.globsframework.core.model.Glob;
import org.globsframework.fix.dictionary.model.FixFieldType;

import java.util.HashMap;
import java.util.Map;

public class FixFieldAccessor {
    private final Map<GlobType, Map<String, Field>> fixToField = new HashMap<>();

    private static Map<String, Field> init(GlobType globType) {
        Map<String, Field> fixToField = new HashMap<>();
        final Field[] fields = globType.getFields();
        for (Field field : fields) {
            final Glob fix = field.findAnnotation(FixFieldType.UNIQUE_KEY);
            if (fix != null) {
                fixToField.put(fix.get(FixFieldType.name), field);
            }
        }
        return fixToField;
    }

    public Map<String, Field> getFixField(GlobType globType) {
        if (globType == null) {
            return Map.of();
        }
        return fixToField.computeIfAbsent(globType, FixFieldAccessor::init);
    }
}
