package org.globsframework.fix.serializer;

/**
 * The buffer a message is rendered into, and how far it is rendered : what a {@link FieldWrite} used to
 * return as an int and now advances in place.
 * <p>
 * One instance per {@link FixWriterImpl}, wrapping its single buffer, reused for every message — writing a
 * field allocates nothing. A writer that has nothing to write leaves {@link #at} where it found it.
 * <p>
 * The point of moving the index here is that {@code writeAt} returns void, which is the shape
 * {@code FromGlobFunction} has: an int-returning method cannot be driven by a generated caller, and the
 * caller is what makes each per-field call site monomorphic. The fields are public and written directly
 * because this is the hot path; the idiom in every writer is to read {@code at} into a local, run the field's
 * bytes through the {@code Utils} helpers, and store it back once at the end.
 */
public final class WriteBuffer {
    public final byte[] buffer;
    public int at;

    public WriteBuffer(byte[] buffer) {
        this.buffer = buffer;
    }
}
