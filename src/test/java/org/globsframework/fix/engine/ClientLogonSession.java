package org.globsframework.fix.engine;

import org.globsframework.core.model.Glob;
import org.globsframework.fix.HeaderType;
import org.globsframework.fix.deserializer.FixMessageValue;
import org.globsframework.fix.dictionary.admin.LogonType;
import org.globsframework.fix.fix44.app.QuoteRequestType;
import org.globsframework.fix.fix44.app.QuoteResponseType;
import org.globsframework.fix.serializer.FixWriter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class ClientLogonSession implements UserLogonSession, Connected {
    private final Shutdown shutdown;
    private final String senderCompID;
    private final String targetCompoID;
    private final Map<String, PriceListener> listeners = new HashMap<>();
    private final List<FixMessageValue> received = new ArrayList<>();

    public ClientLogonSession(Shutdown shutdown,
                              String senderCompID, String targetCompoID) {
        this.shutdown = shutdown;
        this.senderCompID = senderCompID;
        this.targetCompoID = targetCompoID;
    }

    @Override
    public UserSession initiator() {
        return new ClientUserSession(this);
    }

    @Override
    public UserSession acceptor(String senderCompID, String targetCompID) {
        throw new RuntimeException("Expected to be the initiator");
    }

    public void subscribe(String sym, PriceListener priceListener) {
        listeners.put(sym, priceListener);
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

    public int checkReceived(int count) throws InterruptedException {
        synchronized (received) {
            long end = System.currentTimeMillis() + 1000;
            while (count < received.size() && end > System.currentTimeMillis()) {
                received.wait();
            }
        }
        return count;
    }

    private class ClientUserSession implements UserSession {
        private final Connected connected;

        public ClientUserSession(Connected connected) {
            this.connected = connected;
        }


        @Override
        public CompletableFuture<Void> logout() {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public Glob getLogon() {
            return LogonType.create(1000);
        }

        @Override
        public void logonFail() {
        }

        @Override
        public Glob getHeader() {
            return HeaderType.create(senderCompID, targetCompoID);
        }

        @Override
        public AppMessageReceiver connected(FixMessageValue logon, FixWriter appWriter) {
            synchronized (this) {
                connected.connected(targetCompoID, this);
                for (String symbol : listeners.keySet()) {
                    appWriter.write(getHeader().duplicate(), QuoteRequestType.TYPE.instantiate()
                                    .set(QuoteRequestType.quoteReqID, symbol)
                            , null, false);
                }
            }
            return new AppMessageReceiver() {
                @Override
                public void messages(FixMessageValue fixMessageValue) {
                    synchronized (received) {
                        received.add(fixMessageValue);
                        received.notify();
                    }
                    if (fixMessageValue.message() != null) {
                        final Glob message = fixMessageValue.message();
                        if (message.getType() == QuoteResponseType.TYPE) {
                            final String quoteRespId = message.get(QuoteResponseType.quoteRespID);
                            final String bidPx = message.get(QuoteResponseType.bidPx);
                            final PriceListener priceListener = listeners.get(quoteRespId);
                            if (priceListener != null) {
                                priceListener.priceChanged(quoteRespId, bidPx);
                            }
                        }
                    }
                }
            };
        }

    }
}
