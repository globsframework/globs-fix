package org.globsframework.fix.engine;

import java.net.Socket;
import java.util.concurrent.CompletableFuture;

public interface OnNewConnection {
    CompletableFuture<FixLogout> newConnection(Socket socket);
}
