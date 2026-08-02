package org.globsframework.fix.deserializer;

import org.globsframework.core.model.MutableGlob;

/*
A DATA field. Its content can hold any byte, SOH and '=' included, so it is not scanned out of the
read buffer : FixReaderImpl reads exactly the announced number of bytes into a payload of its own.
 */
non-sealed interface DataFieldReader extends FieldReader {

    void read(byte[] payload, MutableGlob data);
}
