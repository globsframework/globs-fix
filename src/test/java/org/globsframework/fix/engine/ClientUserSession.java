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

public class ClientUserSession implements UserSession {
    private final List<FixMessageValue> received = new ArrayList<>();
    private final Map<String, ClientLogonSession.PriceListener> listeners = new HashMap<>();
    private final String senderCompId;
    private final String targetCompId;
    private final Shutdown shutdown;

    public ClientUserSession(String senderCompId, String targetCompId, Shutdown shutdown) {
        this.senderCompId = senderCompId;
        this.targetCompId = targetCompId;
        this.shutdown = shutdown;
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
        return HeaderType.create(senderCompId, targetCompId);
    }

    @Override
    public AppMessageReceiver connected(FixMessageValue logon, FixWriter appWriter) {
        synchronized (this) {
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
                        final ClientLogonSession.PriceListener priceListener = listeners.get(quoteRespId);
                        if (priceListener != null) {
                            priceListener.priceChanged(quoteRespId, bidPx);
                        }
                    }
                }
            }
        };
    }

    public void subscribe(String ccyPair, ClientLogonSession.PriceListener listener) {
        listeners.put(ccyPair, listener);
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

}
