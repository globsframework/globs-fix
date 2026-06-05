package org.globsframework.fix.serializer;

import org.globsframework.core.metamodel.GlobType;
import org.globsframework.core.metamodel.fields.IntegerField;
import org.globsframework.core.metamodel.fields.StringField;
import org.globsframework.core.model.Glob;
import org.globsframework.core.model.MutableGlob;
import org.globsframework.fix.FormatDateTime;
import org.globsframework.fix.Utils;
import org.globsframework.fix.dictionary.FixModel;
import org.globsframework.fix.engine.FixMessage;
import org.globsframework.fix.engine.HeaderDesc;

import java.nio.charset.StandardCharsets;
import java.util.Map;

public class FixWriterImpl implements FixWriter {
    public static final int OFFSET = 32;
    private final byte[] version;
    private final byte[] buffer = new byte[1024 * 1024];
    private final byte[] utcTime = new byte[21];
    private final Publish publish;
    private final Map<GlobType, MessageFieldWrite> writeMap;
    private final Map<GlobType, byte[]> typeToMessageType;
    private final MsgSeqProvider msgSeqProvider;
    private final IntegerField msgSeqNum;
    private final IntegerField checksum;
    private final StringField sendingTime;
    private final FormatDateTime utcFormater;

    public FixWriterImpl(FixModel fixModel, Publish publish, Map<GlobType, MessageFieldWrite> writeMap,
                         Map<GlobType, byte[]> typeToMessageType, MsgSeqProvider msgSeqProvider,
                         IntegerField checksum, HeaderDesc headerDesc, FormatDateTime utcFormater) {
        version = fixModel.getVersion().getBytes(StandardCharsets.ISO_8859_1);
        this.publish = publish;
        this.writeMap = writeMap;
        this.typeToMessageType = typeToMessageType;
        this.msgSeqProvider = msgSeqProvider;
        this.msgSeqNum = headerDesc.seqNumField();
        this.checksum = checksum;
        this.sendingTime = headerDesc.sendingTime();
        this.utcFormater = utcFormater;
    }

    @Override
    synchronized public void write(FixMessage fixMessage) {
        int at = OFFSET;
        final MutableGlob header = fixMessage.getHeader();
        final MutableGlob trailer = fixMessage.getTrailer();
        final MutableGlob message = fixMessage.getBody();
        boolean isSeqNext = header.isNotSet(msgSeqNum);
        if (isSeqNext) {
            header.set(msgSeqNum, msgSeqProvider.next());
        }
        if (fixMessage.resetSeqNum()) {
            msgSeqProvider.reset();
        }
        if (header.isNotSet(sendingTime)) {
            utcFormater.now(utcTime, 0);
            header.set(sendingTime, new String(utcTime));
        }
        int startAt = 0;
        int s = 0;
        try {
            at = write(header, at);
            at = write(fixMessage, at);
            int endAt = write(trailer, at);

            final byte[] msgType = typeToMessageType.get(message.getType());
            int len = endAt - OFFSET + 4 + msgType.length; // add

            final int lenInBytes = Utils.len(len);

            startAt = OFFSET - 2 - version.length - 3 - lenInBytes - 1 - 3 - msgType.length - 1;
            at = startAt;
            buffer[at++] = '8';
            buffer[at++] = '=';
            System.arraycopy(version, 0, buffer, at, version.length);
            at += version.length;
            buffer[at++] = 0x1;
            buffer[at++] = '9';
            buffer[at++] = '=';
            at = Utils.transfertInt(buffer, at, len);
            buffer[at++] = 0x1;

            buffer[at++] = '3';
            buffer[at++] = '5';
            buffer[at++] = '=';
            at = Utils.transfert(buffer, at, msgType);
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
            s = Math.toIntExact(sum % 256);
            buffer[at++] = '=';
            if (s < 10) {
                buffer[at++] = '0';
                buffer[at++] = '0';
                buffer[at++] = (byte) ('0' + s);
            } else if (s < 100) {
                buffer[at++] = '0';
                buffer[at++] = (byte) ('0' + s / 10);
                buffer[at++] = (byte) ('0' + s % 10);
            } else {
                buffer[at++] = (byte) ('0' + s / 100);
                buffer[at++] = (byte) ('0' + (s / 10) % 10);
                buffer[at++] = (byte) ('0' + s % 10);
            }
            buffer[at++] = 0x1;
            publish.publish(buffer, startAt, at - startAt);
        } catch (Exception e) {
            // can be an array out of bound or a socket write error
            if (isSeqNext) {
                msgSeqProvider.revert();
            }
            throw e;
        }
        if (trailer != null && checksum != null) {
            trailer.set(checksum, s);
        }
    }

    private int write(Glob data, int at) {
        if (data != null) {
            final GlobType type = data.getType();
            final MessageFieldWrite fieldWrite = writeMap.get(type);
            if (fieldWrite == null) {
                throw new RuntimeException("Not writers found for type: " + type.getName());
            }
            return fieldWrite.writeAt(buffer, at, data);
        }
        return at;
    }

    private int write(FixMessage data, int at) {
        if (data != null) {
            final GlobType type = data.getBody().getType();
            final MessageFieldWrite fieldWrite = writeMap.get(type);
            if (fieldWrite == null) {
                throw new RuntimeException("Not writers found for type: " + type.getName());
            }
            final FixMessage.UpdatedField updatedFields = data.getUpdatedFields();
            if (updatedFields == null) {
                return fieldWrite.writeAt(buffer, at, data.getBody());
            }
            else {
                return fieldWrite.writeAt(buffer, at, data.getBody(), updatedFields.indexFields(), updatedFields.len());
            }
        }
        return at;
    }
}
