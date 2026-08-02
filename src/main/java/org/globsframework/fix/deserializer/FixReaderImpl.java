package org.globsframework.fix.deserializer;

import org.globsframework.core.metamodel.GlobType;
import org.globsframework.core.metamodel.fields.Field;
import org.globsframework.core.metamodel.fields.IntegerField;
import org.globsframework.core.metamodel.fields.StringField;
import org.globsframework.core.model.Glob;
import org.globsframework.core.model.MutableGlob;
import org.globsframework.fix.Utils;
import org.globsframework.fix.dictionary.FixModel;
import org.globsframework.fix.dictionary.admin.RejectType;
import org.globsframework.fix.dictionary.model.FixFieldType;
import org.globsframework.json.GSonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;

class FixReaderImpl implements FixReader {
    private static final Logger log = LoggerFactory.getLogger(FixReaderImpl.class);
    // a message this engine cannot even write (FixWriterImpl owns a 1 MB buffer) is refused on read.
    private static final int MAX_BODY_LENGTH = 1024 * 1024;
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
    private int pendingDataLength = -1; // >= 0 when the next field is a DATA field of that many bytes
    private int pendingDataTag = -1;
    private byte[] pendingPayload;

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
        while (true) {
            final FixMessageValue value = readOneMessage();
            if (value != null) {
                return value;
            }
            // garbled message (invalid checksum) : ignored per the FIX spec without consuming
            // a seqNum, the gap detection will recover it. Read the next message.
        }
    }

    /*
    returns null when the message is garbled and must be ignored.
     */
    private FixMessageValue readOneMessage() {
        Glob header = readHeader();
        if (isGarbledFraming()) {
            return null;
        }
        if (header == null) {
            // BodyLength ended before the header was complete : garbled message, ignored like an
            // invalid checksum. The body is over so the trailer is still aligned, consume it.
            log.warn("Incomplete header (BodyLength=" + messageLen + ") : message ignored.");
            readChecksum(msgCheck % 256);
            return null;
        }
        if (currentFixStruct == null) {
            // unknown MsgType : consume the message and let the session layer send a Reject
            log.warn("msgType " + msgType + " not expected, message skipped.");
            skipRemaining();
            if (isGarbledFraming() || !readChecksum(msgCheck % 256)) {
                return null;
            }
            return new FixMessageValue(header, null, null,
                    new FixMessageValue.DecodeError(RejectType.SESSION_REJECT_INVALID_MSGTYPE,
                            "MsgType '" + msgType + "' not expected"));
        }
        Glob data;
        FixMessageValue.DecodeError decodeError = null;
        MutableGlob trailer = null;
        try {
            data = readData(currentFixStruct.fixStruct());
        } catch (Exception e) {
            // the framing is still valid : consume the rest of the message and reject it
            log.warn("Fail to decode message of type " + msgType + " : " + e.getMessage(), e);
            try {
                skipRemaining();
            } catch (Exception skipError) {
                // the position inside the message is not trustworthy any more : garbled, ignored
                log.warn("Cannot consume the message after the decode error : " + skipError.getMessage());
                return null;
            }
            data = null;
            decodeError = new FixMessageValue.DecodeError(RejectType.SESSION_REJECT_INCORRECT_DATA_FORMAT,
                    "Fail to decode message : " + e.getMessage());
        }
        if (decodeError == null) {
            if (data == null) {
                log.warn("No data read for " + GSonUtils.encode(header));
                decodeError = new FixMessageValue.DecodeError(RejectType.SESSION_REJECT_INVALID_MSGTYPE,
                        "MsgType '" + msgType + "' not managed");
            }
            trailer = readData(this.trailer);
            if (currentReadId != -1) {
                // a tag no structure of that message knows about : the body is not consumed, and the
                // checksum would be read in the middle of it. Skip it and let the session layer Reject.
                log.warn("Tag " + currentReadId + " not expected in message of type " + msgType + ".");
                decodeError = new FixMessageValue.DecodeError(RejectType.SESSION_REJECT_INVALID_TAG_NUMBER,
                        "Tag '" + currentReadId + "' not expected in message '" + msgType + "'");
                skipRemaining();
            }
        }

        if (isGarbledFraming()) {
            return null;
        }

        int check = msgCheck % 256;
        if (!readChecksum(check)) {
            return null;
        }

        if (trailer != null && checkSumField != null) {
            trailer.set(checkSumField, check);
        }
        return new FixMessageValue(header, data, trailer, decodeError);
    }

    /*
    the body must end exactly on a field boundary : when it does not, BodyLength is wrong and the
    framing cannot be trusted any more. The message is garbled and is ignored, no seqNum is consumed.
     */
    private boolean isGarbledFraming() {
        if (msgReadLen <= messageLen) {
            return false;
        }
        log.warn("Invalid BodyLength " + messageLen + " for msgType " + msgType + " : " + msgReadLen +
                 " bytes read, message ignored.");
        return true;
    }

    /*
    the count comes from the wire and sizes an array before a single element is read : a message cannot
    declare more elements than it has bytes left. Without this a bogus 'NoPartyIDs=999999999' allocates
    a huge array, and the resulting OutOfMemoryError is an Error, not caught as a decode error.
     */
    private void checkGroupCount(int groupCount) {
        final int left = messageLen - msgReadLen;
        if (groupCount < 0 || groupCount > left) {
            throw new RuntimeException("Invalid count " + groupCount + " for group " + currentReadId +
                                       " : only " + left + " bytes left in the message");
        }
    }

    private void skipRemaining() {
        while (readNext()) {
        }
    }

    private boolean readChecksum(int check) {
        // we read the 7 octets for checkum (2 octets) = value (3 octets) + 0x1
        if (pos + 7 > buffer.length) {
            System.arraycopy(buffer, pos, buffer, 0, length - pos);
            length = length - pos;
            pos = 0;
        }
        while (pos + 7 > length) {
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
        if (buffer[pos + 6] != sep) { // '10' '=' 3 digits and the separator
            throw new RuntimeException("Missing SOH " + sep + " at the end of the checksum");
        }
        pos += 7;
        if (checkSum != check) {
            log.warn("Invalid checksum " + checkSum + " != " + check + " : message ignored.");
            return false;
        }
        return true;
    }

    public Glob readHeader() {
        msgCheck = 0;
        currentFixStruct = null;
        msgType = null;
        pendingDataLength = -1;
        pendingDataTag = -1;
        pendingPayload = null;
        // msgReadLen still holds the len of the previous message : without this reset, readNext would
        // refuse to read a message that follows one whose BodyLength happens to reach the sentinel.
        msgReadLen = 0;
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
                                       new String(buffer, equalAt + 1, endAt - equalAt - 1, StandardCharsets.ISO_8859_1));
        }

        //read message len
        if (!readNext()) {
            throw new RuntimeException("Missing len");
        }
//        int msgLenId = currentReadId; //Utils.getIntAt(startAt, equalAt, buffer);
        checkId(currentReadId, 9);
        messageLen = Utils.getIntAt(equalAt + 1, endAt, buffer);
        if (messageLen < 0 || messageLen > MAX_BODY_LENGTH) {
            // also catches the int overflow of getIntAt on a very long value. Everything read from
            // the body is sized against messageLen, we cannot let the peer make it arbitrary.
            throw new RuntimeException("Invalid BodyLength " + messageLen + ", " + MAX_BODY_LENGTH + " max.");
        }
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

    /*
    A length of 0 announces no DATA field at all : the tag that follows is read normally.
     */
    private void armDataField(DataLengthFieldReader lengthReader) {
        final int len = Utils.getIntAt(equalAt + 1, endAt, buffer);
        if (len > 0) {
            pendingDataLength = len;
            pendingDataTag = lengthReader.dataTag();
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
                case DataLengthFieldReader lengthReader -> {
                    armDataField(lengthReader);
                    if (!readNext()) {
                        return;
                    }
                }
                case DataFieldReader dataFieldReader -> {
                    pendingPayload = null; // already consumed by readDataField
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
                case DataLengthFieldReader lengthReader -> {
                    lengthReader.read(equalAt + 1, endAt, buffer, data);
                    armDataField(lengthReader);
                    if (!readNext()) {
                        return data;
                    }
                }
                case DataFieldReader dataFieldReader -> {
                    dataFieldReader.read(pendingPayload, data);
                    pendingPayload = null;
                    if (!readNext()) {
                        return data;
                    }
                }
                case GroupReader groupReader -> {
                    final int groupCount = Utils.getIntAt(equalAt + 1, endAt, buffer);
                    checkGroupCount(groupCount);
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
        if (pendingDataLength >= 0) {
            return readDataField(); // kept out of line : the scan below is the hot path
        }
        // >= and not == : on a wrong BodyLength msgReadLen jumps over messageLen and we would
        // otherwise keep reading the following messages forever. readOneMessage reports the overshoot.
        if (msgReadLen >= messageLen) {
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

    /*
    A DATA field holds any byte, SOH and '=' included : it cannot be scanned, exactly the number of
    bytes announced by the LENGTH field that precedes it is read. The payload is read straight into
    its own array instead of going through the read buffer, so a field larger than the buffer works.
     */
    private boolean readDataField() {
        final int len = pendingDataLength;
        final int dataTag = pendingDataTag;
        pendingDataLength = -1;
        pendingDataTag = -1;
        final int left = messageLen - msgReadLen;
        if (len > left) {
            throw new RuntimeException("Invalid length " + len + " for data field " + dataTag +
                                       " : only " + left + " bytes left in the message");
        }
        readDataTag();
        final int readTag = currentReadId;
        // the payload is consumed even when the tag is not the expected one : the announced length is
        // still the only way to stay aligned, so the message can be rejected without losing the stream
        pendingPayload = readPayload(len);
        readDataSeparator(readTag);
        if (readTag != dataTag) {
            throw new RuntimeException("Data field " + dataTag + " expected after its length but got " + readTag);
        }
        return true;
    }

    private void readDataTag() {
        startAt = pos;
        int at = pos;
        while (true) {
            if (at == length) {
                pos = at;
                fillBuffer();
                at = pos; // fillBuffer may have shifted everything down
                continue;
            }
            if (buffer[at] == '=') {
                break;
            }
            if (at - startAt > 10) {
                throw new RuntimeException("No tag found before the data field, message is garbled.");
            }
            at++;
        }
        equalAt = at;
        int check = msgCheck;
        for (int i = startAt; i <= equalAt; i++) {
            check += buffer[i] & 0xFF;
        }
        msgCheck = check;
        msgReadLen += equalAt - startAt + 1;
        currentReadId = Utils.getIntAt(startAt, equalAt, buffer);
        pos = equalAt + 1;
    }

    private byte[] readPayload(int len) {
        final byte[] payload = new byte[len];
        int copied = Math.min(length - pos, len);
        System.arraycopy(buffer, pos, payload, 0, copied);
        pos += copied;
        while (copied < len) {
            // straight into the payload : the field may be larger than the read buffer
            final int read = reader.read(payload, copied, len - copied);
            if (read <= 0) {
                throw new RuntimeException("Unexpected end of stream");
            }
            copied += read;
        }
        int check = msgCheck;
        for (byte b : payload) {
            check += b & 0xFF;
        }
        msgCheck = check;
        msgReadLen += len;
        return payload;
    }

    private void readDataSeparator(int dataTag) {
        if (pos == length) {
            startAt = pos; // nothing to keep, the payload is already out of the buffer
            fillBuffer();
        }
        if (buffer[pos] != sep) {
            throw new RuntimeException("Data field " + dataTag + " does not end on a separator, its length is wrong.");
        }
        msgCheck += sep & 0xFF;
        msgReadLen++;
        endAt = pos;
        pos++;
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
            final int read = reader.read(buffer, length, buffer.length - length);
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
