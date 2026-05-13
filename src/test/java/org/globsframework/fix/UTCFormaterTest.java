package org.globsframework.fix;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

class UTCFormaterTest {

    @Test
    void testNow() throws InterruptedException {
        final ScheduledExecutorService scheduledExecutorService = Executors.newSingleThreadScheduledExecutor();
        UTCFormater utcFormater = UTCFormater.withAutoRefresh(scheduledExecutorService);
        final byte[] bytes = new byte[21];
        final DateTimeFormatter UTC_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HH:mm:ss.SSS").withZone(ZoneId.of("UTC"));
        final long until = System.currentTimeMillis() + 10 * 1000; // tested with 2 minutes.
        while(System.currentTimeMillis() < until) {
            final Instant now = Instant.now();
            utcFormater.now(bytes, 0, now.toEpochMilli());
            Assertions.assertEquals(UTC_FORMAT.format(ZonedDateTime.ofInstant(now, ZoneId.systemDefault())), new String(bytes));
            Thread.sleep(Duration.of(500, ChronoUnit.MICROS));
        }
    }

    @Test
    void name() {
        UTCFormater utcFormater = UTCFormater.shouldRefresh();
        final Instant now = Instant.now();
        final long epochMilli = now.toEpochMilli();
        final String utc = utcFormater.now(epochMilli);
        Assertions.assertEquals(UTCFormater.toDate(utc),
                ZonedDateTime.ofInstant(now.truncatedTo(ChronoUnit.MILLIS), ZoneId.of("UTC")));
    }
}