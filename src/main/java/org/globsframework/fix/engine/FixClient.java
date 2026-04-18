package org.globsframework.fix.engine;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class FixClient {
    private final String host;
    private final int port;
    private final OnNewConnection onNewConnection;
    private CompletableFuture<FixConnectionFactory.FixLogout> logoutCompletableFuture;

    public FixClient(String host, int port, OnNewConnection onNewConnection) {
        this.host = host;
        this.port = port;
        this.onNewConnection = onNewConnection;
    }

    public void connect() throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(host, port));
        logoutCompletableFuture = onNewConnection.newConnection(socket);
    }

    public CompletableFuture<Boolean> disconnect() throws ExecutionException, InterruptedException, TimeoutException {
        return logoutCompletableFuture.get(2, TimeUnit.SECONDS).close();
    }
}
