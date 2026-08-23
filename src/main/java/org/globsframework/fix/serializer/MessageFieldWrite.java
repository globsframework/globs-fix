package org.globsframework.fix.serializer;

import org.globsframework.core.model.Glob;

/**
 * The writers of one message, header or trailer, in the order the dictionary declares them — which is the
 * order they go on the wire.
 */
public final class MessageFieldWrite {
    private final FieldWrite[] writes;

    public MessageFieldWrite(FieldWrite[] fieldWrites) {
        this.writes = fieldWrites;
    }

    public void writeAt(WriteBuffer out, Glob data) {
        for (FieldWrite fieldWrite : writes) {
            fieldWrite.writeAt(out, data);
        }
    }
}
