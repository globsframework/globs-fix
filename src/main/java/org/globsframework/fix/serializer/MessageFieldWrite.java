package org.globsframework.fix.serializer;

import org.globsframework.core.metamodel.GlobType;
import org.globsframework.core.metamodel.fields.Field;
import org.globsframework.core.model.Glob;
import org.globsframework.core.model.caller.FromGlobCaller;
import org.globsframework.core.model.caller.FromGlobCallerFactory;
import org.globsframework.core.model.caller.FromGlobFunction;

import java.util.Map;

/**
 * The writers of one message, header or trailer, in the order the dictionary declares them — which is the
 * order they go on the wire.
 * <p>
 * Two ways of running them, the same writers in the same order either way:
 * <ul>
 * <li>a {@link FromGlobCaller} when something in this JVM generates one — the calls are unrolled, one
 * monomorphic call site per field, and the values are read out of the Glob by the emitted code;</li>
 * <li>the loop below when nothing does — one megamorphic call site for every writer class in the process,
 * which is exactly what the caller exists to remove.</li>
 * </ul>
 * The order is the map's, so the caller is given it rather than left to walk the fields by index: a GlobType
 * whose declaration order is not the dictionary's is still written in the dictionary's. The fields FIX does
 * not bind are not in the map, and the caller emits no call site for them at all.
 * <p>
 * The loop lets each writer read its own value, through the typed accessor it holds — which is why this asks
 * {@code generatedCallerFor} rather than {@code callerFor} : the {@code LoopFromGlobCaller} core would hand
 * back reads through {@code Glob.getValue(Field)}, at one call site for every field of every type, and is a
 * worse fallback than the one here. See {@link FieldWrite} for why that read must stay inside the writer.
 */
public final class MessageFieldWrite {
    /** null when nothing in this JVM generates : then the loop, over the two arrays below */
    private final FromGlobCaller<WriteBuffer, Void> caller;
    private final FieldWrite[] writes;

    public MessageFieldWrite(GlobType type, Map<Field, FieldWrite> writes) {
        // one insertion-ordered map read twice : the fields and their writers stay in step
        final Field[] order = writes.keySet().toArray(new Field[0]);
        this.writes = writes.values().toArray(new FieldWrite[0]);
        caller = FromGlobCallerFactory.generatedCallerFor("fix.write", type, new Functions(writes), order);
    }

    /** Which of the two is in use — what a test asserting they agree has to be able to tell. */
    boolean isGenerated() {
        return caller != null;
    }

    public void writeAt(WriteBuffer out, Glob data) {
        if (caller != null) {
            caller.call(data, out, null);
            return;
        }
        for (FieldWrite write : writes) {
            write.writeAt(out, data);
        }
    }

    private record Functions(Map<Field, FieldWrite> writes)
            implements FromGlobCallerFactory.Functions<WriteBuffer, Void> {

        @SuppressWarnings("unchecked")
        public <T> FromGlobFunction<T, WriteBuffer, Void> forField(Field field) {
            return (FromGlobFunction<T, WriteBuffer, Void>) (FromGlobFunction<?, WriteBuffer, Void>)
                    writes.get(field);
        }
    }
}
