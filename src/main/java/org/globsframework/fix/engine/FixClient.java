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
    private final FixConnectionFactory fixConnectionFactory;

    public FixClient(String host, int port, FixConnectionFactory fixConnectionFactory) {
        this.host = host;
        this.port = port;
        this.fixConnectionFactory = fixConnectionFactory;
    }

    public CompletableFuture<FixLogout> connectAsConnector() throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(host, port));
        return fixConnectionFactory.newConnection(socket, fixConnectionFactory.createAcceptor());
    }

    public CompletableFuture<FixLogout> connectAsInitiator(String senderComp, String targetComp) throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(host, port));
        return fixConnectionFactory.newConnection(socket, fixConnectionFactory.createInitiator(senderComp, targetComp));
    }
}
