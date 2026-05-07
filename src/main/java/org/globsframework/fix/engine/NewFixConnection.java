package org.globsframework.fix.engine;

import org.globsframework.fix.deserializer.ByteReader;
import org.globsframework.fix.serializer.Publish;

import java.util.concurrent.CompletableFuture;

public interface NewFixConnection {
    CompletableFuture<FixLogout> onNew(ByteReader reader, Publish writer, Shutdown shutdown);
}
