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
        return new ServerPricerUserSession(targetCompID, senderCompID);
    }

    private class ServerPricerUserSession implements UserSession {

        private final String senderCompID;
        private final String targetCompID;

        public ServerPricerUserSession(String senderCompID, String targetCompID) {
            this.senderCompID = senderCompID;
            this.targetCompID = targetCompID;
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
}
