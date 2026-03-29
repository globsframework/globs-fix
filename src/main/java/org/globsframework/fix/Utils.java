package org.globsframework.fix;

import java.nio.charset.StandardCharsets;

public class Utils {

    public static int copy(byte[] buffer, int at, int value) {
        return fastCopy(buffer, at, Integer.toString(value).getBytes(StandardCharsets.US_ASCII));
    }

    public static int fastCopy(byte[] buffer, int at, int value) {
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
            return fastCopy(buffer, at, Integer.toString(value).getBytes(StandardCharsets.US_ASCII));
        }
    }

    public static int copy(byte[] buffer, int at, byte[] data) {
        for (byte d : data) {
            buffer[at++] = d;
        }
        return at;
    }

    public static int fastCopy(byte[] buffer, int at, byte[] data) {
        switch (data.length) {
            case 7 :
                buffer[at + 6] = data[6];
            case 6 :
                buffer[at + 5] = data[5];
            case 5 :
                buffer[at + 4] = data[4];
            case 4 :
                buffer[at + 3] = data[3];
            case 3 :
                buffer[at + 2] = data[2];
            case 2:
                buffer[at + 1] = data[1];
            case 1:
                buffer[at] = data[0];
                return at + data.length;
            default:
                for (byte d : data) {
                    buffer[at++] = d;
                }
                return at;
        }
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

    public static int fastCopy(byte[] buffer, int at, String value) {
        final int length = value.length();
        switch (length) {
            case 7 :
                buffer[at + 6] = cast(value.charAt(6));
            case 6 :
                buffer[at + 5] = cast(value.charAt(5));
            case 5:
                buffer[at + 4] = cast(value.charAt(4));
            case 4:
                buffer[at + 3] = cast(value.charAt(3));
            case 3:
                buffer[at + 2] = cast(value.charAt(2));
            case 2:
                buffer[at + 1] = cast(value.charAt(1));
            case 1:
                buffer[at] = cast(value.charAt(0));
            case 0:
                return at + length;
            default:
                final byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
                System.arraycopy(bytes, 0, buffer, at, bytes.length);
                return at + bytes.length;
        }
    }

    private static byte cast(char value) {
        return value < 0xff ? (byte) value : (byte)'?';
    }
}
