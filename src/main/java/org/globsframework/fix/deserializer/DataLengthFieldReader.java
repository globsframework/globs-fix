package org.globsframework.fix.deserializer;

import org.globsframework.core.model.MutableGlob;

/*
A LENGTH field announcing the size of the DATA field that immediately follows it. Its value drives
the parsing of that field, so it is read whether or not the GlobType binds it.
 */
non-sealed interface DataLengthFieldReader extends FieldReader {

    int dataTag();

    void read(int from, int to, byte[] buffer, MutableGlob data);
}
