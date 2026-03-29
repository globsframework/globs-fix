package org.globsframework.fix.engine;

import java.net.Socket;

public interface OnNewConnection {
    void newConnection(Socket socket);
}
