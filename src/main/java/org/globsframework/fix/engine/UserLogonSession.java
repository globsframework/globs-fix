package org.globsframework.fix.engine;

public interface UserLogonSession {
    UserSession initiator();

    UserSession acceptor(String senderCompID, String targetCompID);
}
