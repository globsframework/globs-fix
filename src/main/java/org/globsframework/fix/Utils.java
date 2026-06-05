package org.globsframework.fix;

import org.globsframework.fix.deserializer.FixMessageValue;
import org.globsframework.fix.deserializer.FixReader;
import org.globsframework.fix.engine.FixMessageListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

public class Utils {
    public static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd-HH:mm:ss.SSS");
    private static final Logger log = LoggerFactory.getLogger(Utils.class);

    public static String toDate(ZonedDateTime dateTime) {
        return dateTime.format(FORMATTER);
    }

    public static int transfertInt(byte[] buffer, int at, int value) {
        if (value < 10) {
            buffer[at] = (byte)('0' + value);
            return at + 1;
        }
        if (value < 100) {
            buffer[at++] = (byte)('0' + value / 10);
            buffer[at++] = (byte)('0' + value % 10);
            return at;
        }
        if (value < 1000) {
            buffer[at++] = (byte)('0' + value / 100);
            buffer[at++] = (byte)('0' + (value / 10) % 10);
            buffer[at++] = (byte)('0' + value % 10);
            return at;
        }
        if (value < 10000) {
            buffer[at++] = (byte)('0' + value / 1000);
            buffer[at++] = (byte)('0' + (value / 100) % 10);
            buffer[at++] = (byte)('0' + (value / 10) % 10);
            buffer[at++] = (byte)('0' + value % 10);
            return at;
        }
        if (value < 100000) {
            buffer[at++] = (byte)('0' + value / 10000);
            buffer[at++] = (byte)('0' + (value / 1000) % 10);
            buffer[at++] = (byte)('0' + (value / 100) % 10);
            buffer[at++] = (byte)('0' + (value / 10) % 10);
            buffer[at++] = (byte)('0' + value % 10);
            return at;
        }
        else {
            return transfert(buffer, at, Integer.toString(value).getBytes(StandardCharsets.ISO_8859_1));
        }
    }

    public static int transfert(byte[] buffer, int at, byte[] data) {
        System.arraycopy(data, 0, buffer, at, data.length);
        return at + data.length;
    }

    public static int len(int len) {
        if (len < 10) {
            return 1;
        }
        if (len < 100) {
            return 2;
        }
        if (len < 1000) {
            return 3;
        }
        if (len < 10000) {
            return 4;
        }
        if (len < 100000) {
            return 5;
        }
        if (len < 1000000) {
            return 6;
        }
        if (len < 10000000) {
            return 7;
        }
        if (len < 100000000) {
            return 8;
        }
        if (len < 1000000000) {
            return 9;
        }
        return 10;
    }

    public static int transfert(byte[] buffer, int at, String value) {
        return transfert(buffer, at, value.getBytes(StandardCharsets.ISO_8859_1));
    }

    public static int getIntAt(int from, int to, byte[] buffer) {
        int value = 0;
        for (int i = from; i < to; i++) {
            value = value * 10 + buffer[i] - '0';
        }
        return value;
    }

    public static Runnable loopRead(FixMessageListener fixMessageListener, FixReader fixReader) {
        return () -> {
            try {
                while (true) {
                    final FixMessageValue read = fixReader.read();
                    fixMessageListener.newMessage(read);
                }
            } catch (Exception e) {
                log.error("End " + e.getMessage(), e);
            }
        };
    }
}
