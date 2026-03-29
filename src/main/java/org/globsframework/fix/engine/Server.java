package org.globsframework.fix.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.CompletableFuture;

public class Server {
    private static final Logger log = LoggerFactory.getLogger(Server.class);
    private final String listenInterface;
    private int port;
    private final OnNewConnection onNewConnection;
    private final ServerSocket serverSocket;
    private CompletableFuture<Boolean> running = new CompletableFuture<>();
    private boolean stopRequested = false;

    public Server(String listenInterface, int port, OnNewConnection onNewConnection) throws IOException {
        this.listenInterface = listenInterface;
        this.port = port;
        this.onNewConnection = onNewConnection;
        serverSocket = new ServerSocket();
    }

    public int getPort() {
        running.join();
        return port;
    }

    public void init() {
        try {
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress(listenInterface, port));
            if (port == 0) {
                port = serverSocket.getLocalPort();
            }
            running.complete(true);
        } catch (IOException e) {
            final String msg = "Failed to initialize server";
            throw new RuntimeException(msg, e);
        }
    }

    public void stop() {
        stopRequested = true;
    }

    public void processConnections() {
        try {
            init();
            while (!stopRequested) {
                final Socket socket = serverSocket.accept();
                socket.setTcpNoDelay(true);
                final int localPort = socket.getLocalPort();
                log.debug("Accepted connection on port " + localPort);
                onNewConnection.newConnection(socket);
            }
        } catch (IOException e) {
            final String msg = "Error processing connections";
            log.error(msg, e);
            throw new RuntimeException(msg, e);
        } finally {
            log.info("Closing server socket.");
        }
    }
}
