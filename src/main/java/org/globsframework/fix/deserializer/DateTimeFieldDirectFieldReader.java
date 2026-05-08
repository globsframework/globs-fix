package org.globsframework.fix.deserializer;

import org.globsframework.core.metamodel.fields.DateTimeField;
import org.globsframework.core.model.Glob;
import org.globsframework.core.model.MutableGlob;
import org.globsframework.fix.Utils;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

class DateTimeFieldDirectFieldReader implements DirectFieldReader {
    public static final ZoneId GMT = ZoneId.of("GMT");
    private final DateTimeField dateTimeField;

    public DateTimeFieldDirectFieldReader(DateTimeField dateTimeField) {
        this.dateTimeField = dateTimeField;
    }

    @Override
    public boolean isSet(Glob data, int currentReadId) {
        return data.isSet(dateTimeField);
    }

    @Override
    public void read(int from, int to, byte[] buffer, MutableGlob data) {
        if (to - from >= 17) {
            ZonedDateTime dateTime = ZonedDateTime.of(
                    LocalDate.of(Utils.getIntAt(from, from + 4, buffer),
                            Utils.getIntAt(from + 4, from + 6, buffer),
                            Utils.getIntAt(from + 6, from + 8, buffer)
                    ),
                    LocalTime.of(
                            Utils.getIntAt(from + 9, from + 11, buffer),
                            Utils.getIntAt(from + 12, from + 14, buffer),
                            Utils.getIntAt(from + 15, from + 17, buffer),
                            to - from == 21 ? Utils.getIntAt(from + 18, from + 21, buffer) * 1_000_000 : 0
                    ), GMT);
            data.set(dateTimeField, dateTime);
        }
    }
}
