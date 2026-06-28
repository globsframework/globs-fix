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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

public class FixServer {
    private static final Logger log = LoggerFactory.getLogger(FixServer.class);
    private final String listenInterface;
    private int port;
    private NewFixConnection newFixConnection;
    private final ServerSocket serverSocket;
    private CompletableFuture<Boolean> running = new CompletableFuture<>();
    private volatile boolean stopRequested = false;
    private final AtomicBoolean stoped = new AtomicBoolean();
    private List<FixLogout> connections = new CopyOnWriteArrayList<>();
    private FixConnectionFactory fixConnectionFactory;

    public FixServer(String listenInterface, int port, FixConnectionFactory fixConnectionFactory) throws IOException {
        this.listenInterface = listenInterface;
        this.port = port;
        this.fixConnectionFactory = fixConnectionFactory;
        serverSocket = new ServerSocket();
    }

    public int getPort() {
        running.join();
        return port;
    }

    public void acceptAsAcceptor() {
        newFixConnection = fixConnectionFactory.createAcceptor();
        init();
        processConnections();
    }

    public void acceptAsInitiator(String senderComp, String targetComp) {
        newFixConnection = fixConnectionFactory.createInitiator(senderComp, targetComp);
        init();
        processConnections();
    }

    private void init() {
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

    private void processConnections() {
        try {
            while (!stopRequested) {
                final Socket socket = serverSocket.accept();
                socket.setTcpNoDelay(true);
                final int localPort = socket.getLocalPort();
                log.debug("Accepted connection on port " + localPort);
                final CompletableFuture<FixLogout> logoutCompletableFuture =
                        fixConnectionFactory.newConnection(socket, newFixConnection);
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
        while (!stoped.get() && System.currentTimeMillis() < endAt) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }
        for (FixLogout connection : connections) {
            connection.close();
        }
    }
}
