package org.globsframework.fix.serializer;

import org.globsframework.core.metamodel.GlobType;
import org.globsframework.core.model.Glob;
import org.globsframework.fix.Utils;
import org.globsframework.fix.dictionary.FixModel;

import java.nio.charset.StandardCharsets;
import java.util.Map;

public class FixWriterImpl implements FixWriter {
    public static final int OFFSET = 32;
    private final byte[] version;
    private final byte[] buffer = new byte[1024 * 10];
    private final Publish publish;
    private final Map<GlobType, FieldWrite> writeMap;
    private final Map<GlobType, byte[]> typeToMessageType;

    public FixWriterImpl(FixModel fixModel, Publish publish, Map<GlobType, FieldWrite> writeMap,
                         Map<GlobType, byte[]> typeToMessageType) {
        version = fixModel.getVersion().getBytes(StandardCharsets.US_ASCII);
        this.publish = publish;
        this.writeMap = writeMap;
        this.typeToMessageType = typeToMessageType;
    }

    public interface Publish {
        void publish(byte[] data, int offset, int length);
    }

    @Override
    synchronized public void write(Glob header, Glob message, Glob trailer) {
        int at = OFFSET;
        at = write(header, at);
        at = write(message, at);
        int endAt = write(trailer, at);

        final byte[] msgType = typeToMessageType.get(message.getType());
        int len = endAt - OFFSET + 4 + msgType.length; // add

        final int lenInBytes = Utils.len(len);

        int startAt = OFFSET - 2 - version.length - 3 - lenInBytes - 1 - 3 - msgType.length - 1;
        at = startAt;
        buffer[at++] = '8';
        buffer[at++] = '=';
        System.arraycopy(version, 0, buffer, at, version.length);
        at += version.length;
        buffer[at++] = 0x1;
        buffer[at++] = '9';
        buffer[at++] = '=';
        at = Utils.fastCopy(buffer, at, len);
        buffer[at++] = 0x1;

        buffer[at++] = '3';
        buffer[at++] = '5';
        buffer[at++] = '=';
        at = Utils.fastCopy(buffer, at, msgType);
        buffer[at++] = 0x1;

        if (at != OFFSET) {
            throw new RuntimeException("Bug in start " + at + "!=32");
        }
        long sum = 0;
        for (int i = startAt; i < endAt; i++) {
            sum += buffer[i];
        }
        at = endAt;
        buffer[at++] = '1';
        buffer[at++] = '0';
        int s = Math.toIntExact(sum % 256);
        buffer[at++] = '=';
        if (s < 10) {
            buffer[at++] = '0';
            buffer[at++] = '0';
            buffer[at++] = (byte) ('0' + s);
        } else if (s < 100) {
            buffer[at++] = '0';
            buffer[at++] = (byte) ('0' + s / 10);
            buffer[at++] = (byte) ('0' + s % 10);
        }
        else {
            buffer[at++] = (byte) ('0' + s / 100);
            buffer[at++] = (byte) ('0' + (s / 10) % 10);
            buffer[at++] = (byte) ('0' + s % 10);
        }
        publish.publish(buffer, startAt, at - startAt);
    }

    private int write(Glob data, int at) {
        if (data != null) {
            final GlobType type = data.getType();
            final FieldWrite fieldWrite = writeMap.get(type);
            if (fieldWrite == null) {
                throw new RuntimeException("Not writers found for type: " + type.getName());
            }
            return fieldWrite.writeAt(buffer, at, data);
        }
        return at;
    }
}
