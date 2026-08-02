package org.globsframework.fix;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/*
Format must end with ss (for secondes)
millsecond will be added in the form '.SSS'
 */

public class FormatDateTime {
    private static final Logger log = LoggerFactory.getLogger(FormatDateTime.class);
    private final ZoneId zoneId;
    private final DateTimeFormatter dateFullWithMilli;
    private final DateTimeFormatter dateFull;
    private final DateTimeFormatter datePartialFormat;
    private final byte[][] secondesBytes;
    private final byte[][] millSecondesBytes;
    private volatile PartialDate[] current;

    record PartialDate(byte[] yyyy, long from, long to) {
        boolean inRange(long at) {
            return at >= from && at < to;
        }
    }

    public static class Builder {
        private String pattern;
        private ZoneId zoneId;

        public static Builder create(String pattern, ZoneId zoneId) {
            return new Builder().withTimeZone(pattern, zoneId);
        }

        public Builder withTimeZone(String pattern, ZoneId zoneId) {
            this.pattern = pattern;
            this.zoneId = zoneId;
            return this;
        }

        public FormatDateTime withAutoRefresh(ScheduledExecutorService scheduledExecutorService){
            return new FormatDateTime(scheduledExecutorService, pattern, zoneId);
        }

        public FormatDateTime shouldRefresh() {
            return new FormatDateTime(null, pattern, zoneId);
        }
    }

    public static FormatDateTime autoRefreshUTC(ScheduledExecutorService scheduledExecutorService) {
        return Builder.create("yyyyMMdd-HH:mm:ss", ZoneId.of("UTC")).withAutoRefresh(scheduledExecutorService);
    }

    public static FormatDateTime shouldRefreshUTC() {
        return Builder.create("yyyyMMdd-HH:mm:ss", ZoneId.of("UTC")).shouldRefresh();
    }

    public ZonedDateTime toDate(String utc) {
        return toDate(utc, true);
    }

    public ZonedDateTime toDate(String utc, boolean withMilli) {
        return ZonedDateTime.parse(utc, withMilli ? dateFullWithMilli : dateFull);
    }

    private FormatDateTime(ScheduledExecutorService scheduledExecutorService, String pattern, ZoneId zoneId) {
        this.zoneId = zoneId;
        dateFullWithMilli = DateTimeFormatter.ofPattern(pattern + ".SSS").withZone(zoneId);
        dateFull = DateTimeFormatter.ofPattern(pattern).withZone(zoneId);
        datePartialFormat = DateTimeFormatter.ofPattern(pattern.substring(0, pattern.length() - 2)).withZone(zoneId);

        millSecondesBytes = new byte[1000][];
        secondesBytes = new byte[60][];
        initArray();
        if (scheduledExecutorService != null) {
            scheduledExecutorService.scheduleAtFixedRate(this::refresh, 20, 20, TimeUnit.SECONDS);
        }
        final ZonedDateTime now = ZonedDateTime.now()
                .truncatedTo(ChronoUnit.MINUTES);
        updateAll(now);
    }

    private void updateAll(ZonedDateTime now) {
        PartialDate[] c = new PartialDate[3];
        c[0] = create(now, -1);
        c[1] = create(now, 0);
        c[2] = create(now, 1);
        this.current = c;
    }

    PartialDate create(ZonedDateTime now, int offset) {
        now = now.plusMinutes(offset);
        final String format = datePartialFormat.format(now);
        final byte[] bytes = format.getBytes(StandardCharsets.ISO_8859_1);
        final long epochMilli = now.toInstant().toEpochMilli();
        return new PartialDate(bytes, epochMilli, epochMilli + 60_000);
    }

    private void initArray() {
        for (int i = 0; i < 1000; i++) {
            millSecondesBytes[i] = (i == 0 ? "000" : i < 10 ? "00" + i : i < 100 ? "0" + i : "" + i)
                    .getBytes(StandardCharsets.ISO_8859_1);
        }
        for (int i = 0; i < 60; i++) {
            secondesBytes[i] = (i == 0 ? "00" : i < 10 ? "0" + i : "" + i)
                    .getBytes(StandardCharsets.ISO_8859_1);
        }
    }

    public void refresh() {
        try {
            final ZonedDateTime now = ZonedDateTime.now();
            final long epochMilli = now.toInstant().toEpochMilli();
            if (this.current[1].inRange(epochMilli)) {
                log.info("no refresh " + now);
                return;
            }
            if (this.current[2].inRange(epochMilli)) {
                log.info("shift refresh " + now);
                PartialDate[] c = new PartialDate[3];
                c[2] = create(now.truncatedTo(ChronoUnit.MINUTES), 1);
                c[1] = this.current[2];
                c[0] = this.current[1];
                this.current = c;
            } else {
                log.info("full refresh " + now);
                updateAll(now);
            }
        } catch (Exception e) {
            log.error("Bug " + e.getMessage(), e);
        }
    }

    public String now(long whenNearNow) {
        return now(whenNearNow, true);
    }

    public String now(long whenNearNow, boolean withMilli) {
        byte[] data = new byte[21];
        now(data, 0, whenNearNow);
        return new String(data, StandardCharsets.ISO_8859_1);
    }

    public String now() {
        return now(System.currentTimeMillis(), true);
    }

    public String now(boolean withMilli) {
        return now(System.currentTimeMillis(), withMilli);
    }

    public int now(byte[] buffer, int at) {
        return now(buffer, at, System.currentTimeMillis());
    }

    public int now(byte[] buffer, int at, long l) {
        return now(buffer, at, l, true);
    }

    public int now(byte[] buffer, int at, long l, boolean withMilli) {
        final PartialDate[] current = this.current;
        long timeInSecond = l / 1000;
        int seconds = (int) (timeInSecond % 60);
        int millis = (int) (l % 1000);
        final byte[] seconde = secondesBytes[seconds];
        final byte[] milliSeconds = millSecondesBytes[millis];
        if (current[1].inRange(l)) {
            return fill(buffer, at, seconde, milliSeconds, current[1], withMilli);
        }
        if (current[2].inRange(l)) {
            return fill(buffer, at, seconde, milliSeconds, current[2], withMilli);
        }
        if (current[0].inRange(l)) {
            return fill(buffer, at, seconde, milliSeconds, current[0], withMilli);
        }
        return fallback(buffer, at, l, withMilli);
    }

    private int fallback(byte[] buffer, int at, long l, boolean withMilli) {
        if (withMilli) {
            final String format = ZonedDateTime.ofInstant(Instant.ofEpochMilli(l), zoneId)
                    .format(dateFullWithMilli);
            log.warn("Date " + format + " not in cache.");
            System.arraycopy(format.getBytes(StandardCharsets.ISO_8859_1), 0, buffer, at, 21);
            return at + 21;
        } else {
            final String format = ZonedDateTime.ofInstant(Instant.ofEpochMilli(l), zoneId)
                    .format(dateFull);
            log.warn("Date " + format + " not in cache.");
            System.arraycopy(format.getBytes(StandardCharsets.ISO_8859_1), 0, buffer, at, 17);
            return at + 17;
        }
    }

    private static int fill(byte[] buffer, int at, byte[] secondes, byte[] milliSecondes, PartialDate partialDate, boolean withMilli) {
        System.arraycopy(partialDate.yyyy(), 0, buffer, at, 15);
        buffer[at + 15] = secondes[0];
        buffer[at + 16] = secondes[1];
        if (withMilli) {
            buffer[at + 17] = '.';
            buffer[at + 18] = milliSecondes[0];
            buffer[at + 19] = milliSecondes[1];
            buffer[at + 20] = milliSecondes[2];
            return at + 21;
        }
        return at + 17;
    }
}
