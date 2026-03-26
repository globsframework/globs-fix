package org.globsframework.fix.engine;

import org.globsframework.core.model.Glob;
import org.globsframework.fix.deserializer.FixMessageValue;
import org.globsframework.fix.deserializer.FixReader;
import org.globsframework.fix.serializer.FixWriter;

public class FixSessionImpl implements Runnable {
    private final FixReader reader;
    private final FixWriter writer;

    public FixSessionImpl(FixReader reader, FixWriter writer) {
        this.reader = reader;
        this.writer = writer;
    }

    @Override
    public void run() {
        while (true) {
            final FixMessageValue read = reader.read();
            final Glob header = read.header();
        }
    }
}
