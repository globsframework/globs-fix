package org.globsframework.fix.engine;

import org.globsframework.core.model.Glob;
import org.globsframework.core.model.MutableGlob;
import org.globsframework.fix.HeaderType;
import org.globsframework.fix.deserializer.FixMessageValue;
import org.globsframework.fix.dictionary.admin.LogonType;
import org.globsframework.fix.fix44.app.QuoteRequestType;
import org.globsframework.fix.fix44.app.QuoteResponseType;
import org.globsframework.fix.serializer.FixWriter;
import org.globsframework.json.GSonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;

public class ServerPricerUserSession implements UserSession {
    private static final Logger log = LoggerFactory.getLogger(ServerPricerUserSession.class);
    private final String senderCompID;
    private final String targetCompID;
    private final Shutdown shutdown;
    private final FixServerTest.PricerImpl pricer;

    public ServerPricerUserSession(String senderCompID, String targetCompID, Shutdown shutdown, FixServerTest.PricerImpl pricer) {
        this.senderCompID = senderCompID;
        this.targetCompID = targetCompID;
        this.shutdown = shutdown;
        this.pricer = pricer;
    }

    public AppMessageReceiver connected(FixMessageValue logon, FixWriter appWriter) {
        return new AppMessageReceiver() {
            @Override
            public void messages(FixMessageValue fixMessageValue) {
                final Glob message = fixMessageValue.message();
                log.info("Got message " + GSonUtils.encode(message) + " " + GSonUtils.encode(fixMessageValue.header()));
                if (message.getType() == QuoteRequestType.TYPE) {
                    final String quoteReqId = message.get(QuoteRequestType.quoteReqID);
                    pricer.subscribe(quoteReqId, value -> {
                        appWriter.write(getHeader(),
                                QuoteResponseType.TYPE.instantiate()
                                        .set(QuoteResponseType.quoteRespID, quoteReqId)
                                        .set(QuoteResponseType.bidPx, value),
                                null, false);
                    });
                }
            }
        };
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
    public MutableGlob getHeader() {
        return HeaderType.create(senderCompID, targetCompID);
    }
}
