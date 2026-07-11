package org.globsframework.fix.deserializer;

import org.globsframework.core.model.Glob;
import org.globsframework.json.GSonUtils;

public record FixMessageValue(Glob header, Glob message, Glob trailer, DecodeError decodeError) {

    public FixMessageValue(Glob header, Glob message, Glob trailer) {
        this(header, message, trailer, null);
    }

    /*
    A message that was well formed at the transport level (valid framing and checksum, header parsed)
    but whose body could not be decoded. The session layer is expected to send a Reject with the
    given SessionRejectReason and consume the sequence number.
     */
    public record DecodeError(int sessionRejectReason, String text) {
    }

    @Override
    public String toString() {
        return String.format("Header: %s, Message: %s, Trailer: %s%s", GSonUtils.encode(header),
                GSonUtils.encode(message, true), trailer == null ? "empty'" : GSonUtils.encode(trailer),
                decodeError == null ? "" : ", DecodeError: " + decodeError);
    }
}
