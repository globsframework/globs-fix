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

public class UTCFormater {
    public static final ZoneId UTC = ZoneId.of("UTC");
    private static final DateTimeFormatter UTC_FULL = DateTimeFormatter.ofPattern("yyyyMMdd-HH:mm:ss.SSS").withZone(UTC);
    private static final DateTimeFormatter UTC_PARTIAL_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HH:mm:").withZone(UTC);
    private static final Logger log = LoggerFactory.getLogger(UTCFormater.class);
    private final byte[][] secondesBytes;
    private final byte[][] millSecondesBytes;
    private volatile PartialDate[] current;

    record PartialDate(byte[] yyyy, long from, long to) {
        boolean inRange(long at) {
            return at >= from && at < to;
        }
    }

    public static UTCFormater withAutoRefresh(ScheduledExecutorService scheduledExecutorService) {
        return new UTCFormater(scheduledExecutorService);
    }

    public static UTCFormater shouldRefresh() {
        return new UTCFormater(null);
    }

    public static ZonedDateTime toDate(String utc) {
        return ZonedDateTime.parse(utc, UTC_FULL);
    }

    private UTCFormater(ScheduledExecutorService scheduledExecutorService) {
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
        final String format = UTC_PARTIAL_FORMAT.format(now);
        final byte[] bytes = format.getBytes(StandardCharsets.US_ASCII);
        final long epochMilli = now.toInstant().toEpochMilli();
        return new PartialDate(bytes, epochMilli, epochMilli + 60_000);
    }

    private void initArray() {
        for (int i = 0; i < 1000; i++) {
            millSecondesBytes[i] = (i == 0 ? "000" : i < 10 ? "00" + i : i < 100 ? "0" + i : "" + i)
                    .getBytes(StandardCharsets.US_ASCII);
        }
        for (int i = 0; i < 60; i++) {
            secondesBytes[i] = (i == 0 ? "00" : i < 10 ? "0" + i : "" + i)
                    .getBytes(StandardCharsets.US_ASCII);
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
        byte[] data = new byte[21];
        now(data, 0, whenNearNow);
        return new String(data);
    }

    public String now() {
        return now(System.currentTimeMillis());
    }

    public int now(byte[] buffer, int at) {
        return now(buffer, at, System.currentTimeMillis());
    }

    public int now(byte[] buffer, int at, long l) {
        final PartialDate[] current = this.current;
        long timeInSecond = l / 1000;
        int seconds = (int) (timeInSecond % 60);
        int millis = (int) (l % 1000);
        final byte[] seconde = secondesBytes[seconds];
        final byte[] milliSeconds = millSecondesBytes[millis];
        if (current[1].inRange(l)) {
            return fill(buffer, at, seconde, milliSeconds, current[1]);
        }
        if (current[2].inRange(l)) {
            return fill(buffer, at, seconde, milliSeconds, current[2]);
        }
        if (current[0].inRange(l)) {
            return fill(buffer, at, seconde, milliSeconds, current[0]);
        }
        return fallback(buffer, at, current, l, seconde, milliSeconds);
    }

    private int fallback(byte[] buffer, int at, PartialDate[] current, long l, byte[] secondes, byte[] milliSecondes) {
        final String format = ZonedDateTime.ofInstant(Instant.ofEpochMilli(l), UTC)
                .format(UTC_FULL);
        log.warn("Date " + format + " not in cache.");
        System.arraycopy(format.getBytes(StandardCharsets.US_ASCII), 0, buffer, at, 21);
        return at + 21;
    }

    private static int fill(byte[] buffer, int at, byte[] secondes, byte[] milliSecondes, PartialDate partialDate) {
        System.arraycopy(partialDate.yyyy(), 0, buffer, at, 15);
        buffer[at + 15] = secondes[0];
        buffer[at + 16] = secondes[1];
        buffer[at + 17] = '.';
        buffer[at + 18] = milliSecondes[0];
        buffer[at + 19] = milliSecondes[1];
        buffer[at + 20] = milliSecondes[2];
        return at + 21;
    }
}
