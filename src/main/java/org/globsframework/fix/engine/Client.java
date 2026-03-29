package org.globsframework.fix.engine;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

public class Client {
    private final String host;
    private final int port;
    private final OnNewConnection onNewConnection;

    public Client(String host, int port, OnNewConnection onNewConnection) {
        this.host = host;
        this.port = port;
        this.onNewConnection = onNewConnection;
    }

    public void connect() throws IOException {
        final Socket socket = new Socket();
        socket.connect(new InetSocketAddress(host, port));
        onNewConnection.newConnection(socket);
    }
}
