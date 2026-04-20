package org.globsframework.fix.engine;

import org.globsframework.core.model.Glob;
import org.globsframework.fix.deserializer.FixMessageValue;
import org.globsframework.fix.serializer.FixWriter;

import java.util.concurrent.CompletableFuture;

public interface UserSession {

    void logonFail();

    Glob getHeader();

    Glob getLogon();

    AppMessageReceiver connected(FixMessageValue logon, FixWriter appWriter);

    CompletableFuture<Void> logout();
}
