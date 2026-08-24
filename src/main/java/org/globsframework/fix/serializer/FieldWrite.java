package org.globsframework.fix.serializer;

import org.globsframework.core.model.Glob;
import org.globsframework.core.model.caller.FromGlobFunction;

/**
 * How one field of a message is rendered on the wire — through either of the two ways
 * {@link MessageFieldWrite} has of running it, over one rendering:
 * <ul>
 * <li>{@link #call} is the caller SPI : the value arrives already read from the Glob, by the emitted code,
 * at a call site that sees this class alone;</li>
 * <li>{@link #writeAt} is the loop : the writer reads the value itself, through the typed accessor it holds.
 * That read stays <em>inside</em> the writer on purpose. Hoisting it into the loop next to the dispatch —
 * over {@code GlobGetAccessor.getValue} — turns one call site per writer class into one for all of them,
 * and measured −27 % on write.</li>
 * </ul>
 * {@code FromGlobFunction} is generic and its value is an Object, so {@code call} casts : that is what pays
 * for a caller unrolling these into one monomorphic call site per field, and it folds away once inlined
 * there. {@code isSet} is not used by any of them — a FIX field is written when it has a value and skipped
 * when it has none, which is {@code isNull}.
 */
public interface FieldWrite extends FromGlobFunction<Object, WriteBuffer, Void> {

    void writeAt(WriteBuffer out, Glob data);
}
