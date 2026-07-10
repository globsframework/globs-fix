package org.globsframework.fix.deserializer;

import org.globsframework.core.metamodel.GlobType;
import org.globsframework.core.metamodel.fields.Field;
import org.globsframework.core.metamodel.fields.IntegerField;
import org.globsframework.core.metamodel.fields.StringField;
import org.globsframework.core.model.Glob;
import org.globsframework.core.model.MutableGlob;
import org.globsframework.fix.Utils;
import org.globsframework.fix.dictionary.FixModel;
import org.globsframework.fix.dictionary.model.FixFieldType;
import org.globsframework.json.GSonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;

class FixReaderImpl implements FixReader {
    private static final Logger log = LoggerFactory.getLogger(FixReaderImpl.class);
    private final FixStruct header;
    private final FixStruct trailer;
    private final Map<String, FixMessageStructure> messagesFixStruct;
    private final FixMessageStructure[] oneLetter;
    private final FixMessageStructure[][] twoLetters;
    private final ByteReader reader;
    private final byte[] buffer = new byte[10024];
    private final FixModel fixModel;
    private final byte sep;
    private final byte[] version;
    private final IntegerField checkSumField;
    private final StringField msgTypeField;
    private int pos = 0;
    private int length = 0;
    private int startAt;
    private int equalAt;
    private int endAt;
    private int msgCheck;
    private int msgReadLen;
    private int messageLen;
    private int currentReadId;
    private String msgType;
    private FixMessageStructure currentFixStruct;

    public FixReaderImpl(ByteReader reader, Map<String, FixMessageStructure> messageFixStruct,
                         FixStruct fixHeader, FixStruct fixTrailer, FixModel fixModel, byte sep) {
        this.reader = reader;
        this.trailer = fixTrailer;
        version = fixModel.getVersion().getBytes(StandardCharsets.ISO_8859_1);
        this.messagesFixStruct = messageFixStruct;
        this.header = fixHeader;
        this.fixModel = fixModel;
        this.sep = sep;
        msgTypeField = header.getType() != null ? Arrays.stream(header.getType().getFields())
                                                  .filter(f -> f.findOptAnnotation(FixFieldType.UNIQUE_KEY)
                                                               .map(FixFieldType.name).filter(s -> s.equals("MsgType")).isPresent())
                                                  .findFirst().map(Field::asStringField)
                                                  .orElse(null) : null;
        checkSumField = trailer.getType() != null ? Arrays.stream(trailer.getType().getFields())
                                                    .filter(f -> f.findOptAnnotation(FixFieldType.UNIQUE_KEY)
                                                                 .map(FixFieldType.name).filter(s -> s.equals("CheckSum")).isPresent())
                                                    .findFirst().map(Field::asIntegerField)
                                                    .orElse(null) : null;

        oneLetter = new FixMessageStructure[256];
        twoLetters = new FixMessageStructure[256][];
        for (Map.Entry<String, FixMessageStructure> entry : messageFixStruct.entrySet()) {
            final String key = entry.getKey();
            final int firstLetter = key.charAt(0) & 0xFF;
            if (key.length() == 1) {
                oneLetter[firstLetter] = entry.getValue();
            } else if (key.length() == 2) {
                FixMessageStructure[] twoLetter = twoLetters[firstLetter];
                if (twoLetter == null) {
                    twoLetter = new FixMessageStructure[256];
                    twoLetters[firstLetter] = twoLetter;
                }
                twoLetter[key.charAt(1) & 0xff] = entry.getValue();
            }
        }
    }

    @Override
    public FixMessageValue read() {
        Glob header = readHeader();
        if (currentFixStruct == null) {
            throw new RuntimeException("msgType " + msgType + " not expected.");
        }
        Glob data = readData(currentFixStruct.fixStruct());
        if (data == null) {
            log.warn("No data read for " + GSonUtils.encode(header));
        }

        MutableGlob trailer = readData(this.trailer);

        int check = msgCheck % 256;

        readChecksum(check);

        if (checkSumField != null) {
            trailer.set(checkSumField, check);
        }
        return new FixMessageValue(header, data, trailer);
    }

    private void readChecksum(int check) {
        // we read the 7 octets for checkum (2 octets) = value (3 octets) + 0x1
        if (pos + 7 > buffer.length) {
            System.arraycopy(buffer, pos, buffer, 0, buffer.length - pos);
            length = length - pos;
            pos = 0;
        }
        while (pos + 6 > length) {
            int read = reader.read(buffer, length, buffer.length - length);
            if (read <= 0) {
                throw new RuntimeException("Unexpected end of stream");
            }
            length += read;
        }
        int checkSumId = Utils.getIntAt(pos, pos + 2, buffer);
        if (checkSumId != 10) {
            throw new RuntimeException("Invalid checksum id " + checkSumId + " != 10");
        }
        int checkSum = Utils.getIntAt(pos + 3, pos + 6, buffer);
        if (checkSum != check) {
            throw new RuntimeException("Invalid checksum " + checkSum + " != " + check);
        }
        pos += 7;
    }

    public Glob readHeader() {
        msgCheck = 0;
        messageLen = 1000; // first read break on separator and we don't know yet the message len
        // read fix version
        if (!readNext()) {
            throw new RuntimeException("Missing FIX header");
        }
//        int fixId = Utils.getIntAt(startAt, equalAt, buffer);
        checkId(currentReadId, 8);
        if (!Arrays.equals(version, 0, version.length, buffer,
                equalAt + 1, endAt)) {
            throw new RuntimeException("invalid version. " + fixModel.getVersion() + " was expected but got " +
                                       new String(buffer, equalAt + 1, endAt));
        }

        //read message len
        if (!readNext()) {
            throw new RuntimeException("Missing len");
        }
//        int msgLenId = currentReadId; //Utils.getIntAt(startAt, equalAt, buffer);
        checkId(currentReadId, 9);
        messageLen = Utils.getIntAt(equalAt + 1, endAt, buffer);
        msgReadLen = 0;
        if (!readNext()) {
            return null;
        }
//        int msgTypeId = Utils.getIntAt(startAt, equalAt, buffer);
        checkId(currentReadId, 35);
        initMsgType();

        if (!readNext()) {
            return null;
        }
        final MutableGlob glob = readData(header);
        if (msgTypeField != null) {
            glob.set(msgTypeField, msgType);
        }
        return glob;
    }

    private void initMsgType() {
        final int len = endAt - equalAt - 1;
        if (len == 1) {
            currentFixStruct = oneLetter[buffer[equalAt + 1] & 0xFF];
        } else if (len == 2) {
            final FixMessageStructure[] twoLetter = twoLetters[buffer[equalAt + 1] & 0xFF];
            if (twoLetter == null) {
                currentFixStruct = null;
            } else {
                currentFixStruct = twoLetter[buffer[equalAt + 2] & 0xFF];
            }
        } else {
            currentFixStruct = messagesFixStruct.get(new String(buffer, equalAt + 1, len, StandardCharsets.ISO_8859_1));
        }
        if (currentFixStruct != null) {
            msgType = currentFixStruct.fixCode();
        } else {
            msgType = new String(buffer, equalAt + 1, len, StandardCharsets.ISO_8859_1);
        }
    }

    private static void checkId(int actualId, int wantedId) {
        if (actualId != wantedId) {
            throw new RuntimeException("Expect code " + wantedId + " but got " + actualId);
        }
    }

    MutableGlob readData(FixStruct fixStruct) {
        final GlobType type = fixStruct.getType();
        if (type == null) {
            skip(fixStruct);
            log.warn("No GlobType defined.");
            return null;
        } else {
            return read(fixStruct, type.instantiate());
        }
    }

    private void skip(FixStruct fixStruct) {
        while (currentReadId != -1) {
            final FieldReader fieldReader = fixStruct.getFieldReader(currentReadId);
            if (fieldReader == null) { // do not belong to this object, so it belong to one ot it's parent
                return;
            }
            switch (fieldReader) {
                case ComponentReader componentReader -> {
                    final FixStruct component = componentReader.getComponent();
                    readData(component);
                }
                case DirectFieldReader directFieldReader -> {
                    if (!readNext()) {
                        return;
                    }
                }
                case GroupReader groupReader -> {
                    final int groupCount = Utils.getIntAt(equalAt + 1, endAt, buffer);
                    if (groupCount == 0) {
                        readNext();
                    } else {
                        if (!readNext()) {
                            throw new RuntimeException("End message reached");
                        }
                        skip(groupReader.sub());
                    }
                }
            }
        }
    }

    private MutableGlob read(FixStruct fixStruct, MutableGlob data) {
        while (currentReadId != -1) {
            final FieldReader fieldReader = fixStruct.getFieldReader(currentReadId);
            if (fieldReader == null) { // do not belong to this object, so it belong to one ot it's parent
                return data;
            }
            if (fieldReader.isSet(data, currentReadId)) {
                return data;
            }
            switch (fieldReader) {
                case ComponentReader componentReader -> {
                    final FixStruct component = componentReader.getComponent();
                    MutableGlob sub = componentReader.get(data);
                    if (sub != null) {// this code is to protect against badly ordered fields
                        componentReader.update(read(component, sub), data);
                    } else {
                        final GlobType type = component.getType();
                        if (type != null) {
                            componentReader.update(read(component, type.instantiate()), data);
                        } else {
                            skip(component);
                        }
                    }
                }
                case DirectFieldReader directFieldReader -> {
                    directFieldReader.read(equalAt + 1, endAt, buffer, data);
                    if (!readNext()) {
                        return data;
                    }
                }
                case GroupReader groupReader -> {
                    final int groupCount = Utils.getIntAt(equalAt + 1, endAt, buffer);
                    Glob[] group = new Glob[groupCount];
                    if (groupCount == 0) {
                        readNext();
                    } else {
                        if (!readNext()) {
                            throw new RuntimeException("End message reached");
                        }
                        for (int i = 0; i < groupCount; i++) {
                            group[i] = readData(groupReader.sub());
                        }
                    }
                    groupReader.update(group, data);
                }
            }
        }
        return data;
    }

    public boolean readNext() {
        if (messageLen == msgReadLen) {
            currentReadId = -1;
            return false;
        }
        startAt = pos;
        boolean equalFound = false;
        int lMsgCheck = msgCheck;
        int lMsgReadLen = msgReadLen;
        byte[] lBuffer = buffer;
        while (true) {
            int lPos = pos;
            int lLength = length;
            while (lPos < lLength) {
                lMsgCheck += (lBuffer[lPos] & 0xFF);
                lMsgReadLen++;
                if (!equalFound) {
                    if (lBuffer[lPos] == '=') {
                        equalFound = true;
                        equalAt = lPos;
                    }
                } else if (lBuffer[lPos] == sep) {
                    endAt = lPos;
                    currentReadId = Utils.getIntAt(startAt, equalAt, buffer);
                    pos = lPos + 1;
                    msgCheck = lMsgCheck;
                    msgReadLen = lMsgReadLen;
                    return true;
                }
                lPos++;
            }
            pos = lPos;
            fillBuffer();
        }
    }

    private void fillBuffer() {
        if (length == buffer.length) {
            if (startAt == 0) {
                throw new RuntimeException("Bug : buffer is not expected to be more then " + buffer.length + " byte.");
            }
            System.arraycopy(buffer, startAt, buffer, 0, buffer.length - startAt);
            equalAt = equalAt - startAt;
            length = buffer.length - startAt;
            pos = pos - startAt;
            startAt = 0;
        }
        if (pos != startAt) {
            final int read = reader.read(buffer, length, buffer.length - pos);
            if (read == -1) {
                throw new RuntimeException("Unexpected end of stream");
            }
            length += read;
        } else {
            final int read = reader.read(buffer, 0, buffer.length);
            if (read == -1) {
                throw new RuntimeException("client disconnected");
            }
            pos = 0;
            length = read;
            startAt = 0;
        }
    }

    public void initBuffer(byte[] initialBuffer, int len) {
        System.arraycopy(initialBuffer, 0, buffer, 0, len);
        length = len;
    }
}
