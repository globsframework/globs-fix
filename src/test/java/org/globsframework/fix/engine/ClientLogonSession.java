package org.globsframework.fix.engine;

public class ClientLogonSession implements UserLogonSession, Connected {
    private final Shutdown shutdown;
    private final String senderCompID;
    private final String targetCompoID;

    public ClientLogonSession(Shutdown shutdown,
                              String senderCompID, String targetCompoID) {
        this.shutdown = shutdown;
        this.senderCompID = senderCompID;
        this.targetCompoID = targetCompoID;
    }

    @Override
    public UserSession initiator() {
        return null;
    }

    @Override
    public UserSession acceptor(String senderCompID, String targetCompID) {
        throw new RuntimeException("Expected to be the initiator");
    }

    public void subscribe(String sym, PriceListener priceListener) {
    }

    @Override
    public void connected(String targetCompID, UserSession clientUserSession) {
//            log.info("Connect " + targetCompID + " register " + listeners.size() + " listener");
//            for (Map.Entry<String, PriceListener> stringPriceListenerEntry : listeners.entrySet()) {
//                log.info("Register " + stringPriceListenerEntry.getKey());
//                clientUserSession.subscribe(stringPriceListenerEntry.getKey(), stringPriceListenerEntry.getValue());
//            }
    }

    public interface PriceListener {
        void priceChanged(String symbol, String bidPx);
    }


}
