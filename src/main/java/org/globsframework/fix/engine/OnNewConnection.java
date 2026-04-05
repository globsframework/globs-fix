package org.globsframework.fix.engine;

import java.net.Socket;
import java.util.concurrent.CompletableFuture;

public interface OnNewConnection {
    CompletableFuture<FixConnectionFactory.FixLogout> newConnection(Socket socket);
}
