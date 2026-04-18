package org.globsframework.fix.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

public class FixServer {
    private static final Logger log = LoggerFactory.getLogger(FixServer.class);
    private final String listenInterface;
    private int port;
    private final OnNewConnection onNewConnection;
    private final ServerSocket serverSocket;
    private CompletableFuture<Boolean> running = new CompletableFuture<>();
    private volatile boolean stopRequested = false;
    private final AtomicBoolean stoped = new AtomicBoolean();
    private List<FixConnectionFactory.FixLogout> connections = new ArrayList<FixConnectionFactory.FixLogout>();

    public FixServer(String listenInterface, int port, OnNewConnection onNewConnection) throws IOException {
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

    public void processConnections() {
        try {
            init();
            while (!stopRequested) {
                final Socket socket = serverSocket.accept();
                socket.setTcpNoDelay(true);
                final int localPort = socket.getLocalPort();
                log.debug("Accepted connection on port " + localPort);
                final CompletableFuture<FixConnectionFactory.FixLogout> logoutCompletableFuture = onNewConnection.newConnection(socket);
                logoutCompletableFuture.thenAccept(fixLogout -> {
                    fixLogout.registerOnClosed(() -> {
                        connections.remove(fixLogout);
                    });
                    connections.add(fixLogout);
                });
            }
        } catch (IOException e) {
            final String msg = "Error processing connections";
            log.error(msg, e);
            throw new RuntimeException(msg, e);
        } finally {
            stoped.set(true);
            log.info("Closing server socket.");
        }
    }

    public void shutdown() {
        stopRequested = true;
        try {
            serverSocket.close();
        } catch (IOException _) {
        }

        long endAt = System.currentTimeMillis() + 1000;
        while (!stoped.get() && System.currentTimeMillis() > endAt) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }
        for (FixConnectionFactory.FixLogout connection : connections) {
            connection.close();
        }
    }
}
