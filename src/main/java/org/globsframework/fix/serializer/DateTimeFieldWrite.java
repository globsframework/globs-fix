package org.globsframework.fix.serializer;

import org.globsframework.core.model.Glob;
import org.globsframework.core.model.globaccessor.get.GlobGetDateTimeAccessor;
import org.globsframework.fix.Utils;

import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

record DateTimeFieldWrite(byte[] id, GlobGetDateTimeAccessor accessor) implements FieldWrite {
    public static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd-HH:mm:ss.SSS");
    public static final ZoneId GMT = ZoneId.of("GMT");

    public DateTimeFieldWrite(int id, GlobGetDateTimeAccessor getAccessor) {
        this(Integer.toString(id).getBytes(StandardCharsets.ISO_8859_1), getAccessor);
    }

    @Override
    public void writeAt(WriteBuffer out, Glob data) {
        final ZonedDateTime value = accessor.get(data);
        if (value != null) {
            final byte[] buffer = out.buffer;
            int at = Utils.transfert(buffer, out.at, id);
            buffer[at++] = '=';
            final ZonedDateTime dateTime = value.withZoneSameInstant(GMT);
            at = Utils.transfertInt(buffer, at, dateTime.getYear());
            final int month = dateTime.getMonthValue();
            at = write2Char(buffer, at, month);
            final int dayOfMonth = dateTime.getDayOfMonth();
            at = write2Char(buffer, at, dayOfMonth);
            buffer[at++] = '-';
            at = write2Char(buffer, at, dateTime.getHour());
            buffer[at++] = ':';
            at = write2Char(buffer, at, dateTime.getMinute());
            buffer[at++] = ':';
            at = write2Char(buffer, at, dateTime.getSecond());
            buffer[at++] = '.';
            final int milli = dateTime.getNano() / 1_000_000;
            if (milli < 10) {
                buffer[at++] = '0';
                buffer[at++] = '0';
                buffer[at++] = (byte) ('0' + milli);
            } else if (milli < 100) {
                buffer[at++] = '0';
                at = Utils.transfertInt(buffer, at, milli);
            } else {
                at = Utils.transfertInt(buffer, at, milli);
            }
            buffer[at++] = 0x1;
            out.at = at;
        }
    }

    private static int write2Char(byte[] buffer, int at, int dayOfMonth) {
        if (dayOfMonth < 10) {
            buffer[at++] = '0';
            buffer[at++] = (byte) ('0' + dayOfMonth);
        }
        else {
            at = Utils.transfertInt(buffer, at, dayOfMonth);
        }
        return at;
    }
}
