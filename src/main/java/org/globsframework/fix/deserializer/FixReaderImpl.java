package org.globsframework.fix.deserializer;

import org.globsframework.core.metamodel.fields.Field;
import org.globsframework.core.metamodel.fields.StringField;
import org.globsframework.core.model.Glob;
import org.globsframework.core.model.MutableGlob;
import org.globsframework.fix.dictionary.FixModel;
import org.globsframework.fix.dictionary.model.FixFieldType;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;

class FixReaderImpl implements FixReader {
    public static final int lastId = 10;
    private final FixStruct header;
    private final Map<String, FixStruct> messages;
    private final ByteReader reader;
    private final byte[] buffer = new byte[10024];
    private final FixModel fixModel;
    private final byte sep;
    private final byte[] version;
    private int pos = 0;
    private int length = 0;
    private int startAt;
    private int equalAt;
    private int endAt;
    private int msgCheck;
    private int msgReadLen;
    private int messageLen;
    private int currentReadId;
    private StringField msgTypeField;

    public FixReaderImpl(ByteReader reader, Map<String, FixStruct> messageFixStruct, FixStruct fixHeader, FixModel fixModel, byte sep) {
        this.reader = reader;
        version = fixModel.getVersion().getBytes(StandardCharsets.US_ASCII);
        this.messages = messageFixStruct;
        this.header = fixHeader;
        this.fixModel = fixModel;
        this.sep = sep;
        msgTypeField = Arrays.stream(fixHeader.getType().getFields())
                .filter(f -> f.findOptAnnotation(FixFieldType.UNIQUE_KEY)
                        .map(FixFieldType.name).filter(s -> s.equals("MsgType")).isPresent())
                .findFirst().map(Field::asStringField)
                .orElseThrow(() -> new IllegalStateException("MsgType field not found"));
    }


    @Override
    public FixMessageValue read() {
        Glob header = readHeader();
        final String msgType = header.get(msgTypeField);
        final FixStruct messageStruct = messages.get(msgType);
        Glob data = readData(messageStruct);
        while (readNext());
        int check = msgCheck % 256;
        // we read the 6 octets for checkum (2 octets) = value (3 octets)
        if (pos + 6 > buffer.length) {
            System.arraycopy(buffer, pos, buffer, 0, buffer.length - pos);
            length = length - pos;
            pos = 0;
        }
        while (pos + 5 > length) {
            int read = reader.read(buffer, length, buffer.length - length);
            if (read <= 0) {
                throw new RuntimeException("Unexpected end of stream");
            }
        }
        int checkSumId = getIntAt(pos, pos + 2, buffer);
        if (checkSumId != 10) {
            throw new RuntimeException("Invalid checksum id " + checkSumId + " != 10");
        }
        int checkSum = getIntAt(pos + 3, pos + 6, buffer);
        if (checkSum != check) {
            throw new RuntimeException("Invalid checksum " + checkSum + " != " + check);
        }
        return new FixMessageValue(header, data, null);
    }

    public Glob readHeader() {
        msgCheck = 0;
        messageLen = 1000; // first read break on separator and we don't known yet the message len
        // read fix version
        readNext();
        int fixId = getIntAt(startAt, equalAt, buffer);
        // todo : check the fieldId
        if (!Arrays.equals(version, 0, version.length, buffer,
                equalAt + 1, endAt)) {
            throw new RuntimeException("invalid version. " + fixModel.getVersion() + " was expected but gor " +
                                       new String(buffer, equalAt + 1, endAt));
        }

        //read message len
        readNext();
        int msgLenId = getIntAt(startAt, equalAt, buffer);
        // todo : check the fieldId
        messageLen = getIntAt(equalAt + 1, endAt, buffer);
        msgReadLen = 0;
        if (!readNext()) {
            return null;
        }
        return readData(header);
    }

    Glob readData(FixStruct fixStruct) {
        final MutableGlob data = fixStruct.getType().instantiate();
        while (currentReadId != lastId) {
            final FieldReader fieldReader = fixStruct.getFieldReader(currentReadId);
            if (fieldReader == null) { // do not belong to this object, so it belong to one ot it's parent
                return data;
            }
            if (fieldReader.isSet(data)) {
                return data;
            }
            switch (fieldReader) {
                case ComponentReader componentReader -> {
                    final FixStruct component = componentReader.getComponent();
                    componentReader.update(readData(component), data);
                }
                case DirectFieldReader directFieldReader -> {
                    directFieldReader.read(equalAt + 1, endAt, buffer, data);
                    if (!readNext()) {
                        return data;
                    }
                }
                case GroupReader groupReader -> {
                    final int groupCount = getIntAt(equalAt + 1, endAt, buffer);
                    Glob[] group = new Glob[groupCount];
                    for (int i = 0; i < groupCount; i++) {
                        group[i] = readData(groupReader.sub());
                    }
                    groupReader.update(group, data);
                }
            }
        }
        return data;
    }

    static int getIntAt(int from, int to, byte[] buffer) {
        int value = 0;
        for (int i = from; i < to; i++) {
            value = value * lastId + buffer[i] - '0';
        }
        return value;
    }


    public boolean readNext() {
        if (messageLen == msgReadLen) {
            return false;
        }
        startAt = pos;
        boolean equalFound = false;
        while (true) {
            while (pos < length) {
                msgCheck += buffer[pos];
                msgReadLen++;
                if (!equalFound) {
                    if (buffer[pos] == '=') {
                        equalFound = true;
                        equalAt = pos;
                    }
                } else if (buffer[pos] == sep) {
                    endAt = pos;
                    currentReadId = getIntAt(startAt, equalAt, buffer);
                    pos++;
                    return true;
                }
                pos++;
            }
            if (length == buffer.length) {
                if (startAt == 0) {
                    throw new RuntimeException("Bug : buffer is not expected to be more then " + buffer.length + " byte.");
                }
                System.arraycopy(buffer, startAt, buffer, 0, buffer.length - startAt);
                equalAt = equalAt - startAt;
                pos = pos - startAt;
                startAt = 0;
                length = 0;
            }
            final int read = reader.read(buffer, length, buffer.length - pos);
            if (read == -1) {
                throw new RuntimeException("Unexpected end of stream");
            }
            length += read;
        }
    }
}
