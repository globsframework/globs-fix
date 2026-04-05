package org.globsframework.fix.deserializer;

import org.globsframework.core.model.Glob;
import org.globsframework.json.GSonUtils;

public record FixMessageValue(Glob header, Glob message, Glob trailer) {

    @Override
    public String toString() {
        return String.format("Header: %s, Message: %s, Trailer: %s", GSonUtils.encode(header),
                GSonUtils.encode(message), trailer == null ? "empty'" : GSonUtils.encode(trailer));
    }
}
