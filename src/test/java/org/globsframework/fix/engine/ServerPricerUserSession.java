package org.globsframework.fix.engine;

import org.globsframework.core.model.Glob;
import org.globsframework.core.model.MutableGlob;
import org.globsframework.fix.HeaderType;
import org.globsframework.fix.deserializer.FixMessageValue;
import org.globsframework.fix.dictionary.admin.LogonType;
import org.globsframework.fix.fix44.app.ExecutionReportType;
import org.globsframework.fix.fix44.app.NewOrderSingleType;
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
                        FixMessage fixMessage = FixMessageImpl.fromType(getHeader(), QuoteResponseType.TYPE, null);
                        fixMessage.update(QuoteResponseType.quoteRespID, quoteReqId);
                        fixMessage.update(QuoteResponseType.bidPx, value);
                        appWriter.write(fixMessage);
                    });
                } else if (message.getType() == NewOrderSingleType.TYPE) {
                    FixMessage fixMessage = FixMessageImpl.fromType(getHeader(), ExecutionReportType.TYPE, null);
                    fixMessage.update(ExecutionReportType.clOrdID, message.get(NewOrderSingleType.clOrdID));
                    appWriter.write(fixMessage);
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
        return LogonType.create(10);
    }

    @Override
    public void logonFail() {
    }

    @Override
    public MutableGlob getHeader() {
        return HeaderType.create(senderCompID, targetCompID);
    }
}
