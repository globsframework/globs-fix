package org.globsframework.fix.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class ServerUserLogonSession implements UserLogonSession {
    private static final Logger log = LoggerFactory.getLogger(ServerUserLogonSession.class);
    private final Shutdown shutdown;
    private final FixServerTest.Pricer pricer;

    public ServerUserLogonSession(Shutdown shutdown, FixServerTest.Pricer pricer) {
        this.shutdown = shutdown;
        this.pricer = pricer;
    }

    @Override
    public UserSession initiator() {
        throw new RuntimeException("Pricer side is acceptor");
    }

    @Override
    public UserSession acceptor(String senderCompID, String targetCompID) {
        // accept connection en inverse sender and target
        return null;
    }

}
