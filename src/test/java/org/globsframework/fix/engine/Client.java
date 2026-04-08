package org.globsframework.fix.engine;

import org.globsframework.core.metamodel.GlobModel;
import org.globsframework.core.metamodel.impl.DefaultGlobModel;
import org.globsframework.fix.HeaderType;
import org.globsframework.fix.TrailerType;
import org.globsframework.fix.deserializer.BasicMsgSeqProvider;
import org.globsframework.fix.deserializer.DeserializerFixReaderBuilder;
import org.globsframework.fix.dictionary.FixModel;
import org.globsframework.fix.dictionary.xml.FieldFactoryImpl;
import org.globsframework.fix.dictionary.xml.ReadFixDictionary;
import org.globsframework.fix.fix44.app.QuoteRequestType;
import org.globsframework.fix.fix44.app.QuoteResponseType;
import org.globsframework.fix.serializer.SerializerFixWriterBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

public class Client {
    private static final Logger log = LoggerFactory.getLogger(Client.class);

    public static void main(String[] args) throws Exception {
        CompletableFuture<FixServerTest.TestUserClientLogonSession> completableFuture = new CompletableFuture<>();
        final BasicMsgSeqProvider clientMsgSeqProvider = new BasicMsgSeqProvider();
        final FixServerTest.InMemoryClientSeqMsgId inMemoryClientSeqMsgId = new FixServerTest.InMemoryClientSeqMsgId();
        final FixServerTest.ClientUserLogonSessionFactory userLogonSessionFactory = new FixServerTest.ClientUserLogonSessionFactory(completableFuture::complete);
        final FixModel fixModel = ReadFixDictionary.parse("fix44", () ->
                new InputStreamReader(FixServer.class.getClassLoader().getResourceAsStream("FIX44.xml"),
                        StandardCharsets.UTF_8), new FieldFactoryImpl());

        final GlobModel globModel = new DefaultGlobModel(QuoteRequestType.TYPE, QuoteResponseType.TYPE);

        final DefaultSerializerProvider serializerProvider = new DefaultSerializerProvider(
                DeserializerFixReaderBuilder.create(fixModel, globModel, HeaderType.TYPE, TrailerType.TYPE),
                SerializerFixWriterBuilder.create(fixModel, globModel, HeaderType.TYPE, TrailerType.TYPE)
        );

        final ExecutorService executorService = Executors.newCachedThreadPool();
        final ScheduledExecutorService scheduledExecutorService = Executors.newSingleThreadScheduledExecutor();
        final FixClient fixClient = new FixClient("localhost", 5456,
                new FixConnectionFactory(
                        new NewInitiatorFixConnectionImpl(executorService, scheduledExecutorService, userLogonSessionFactory,
                                (String senderCompID, String targetCompID) -> new CacheProvider.SeqNumAndCache(NoCachedData.INSTANCE, clientMsgSeqProvider, inMemoryClientSeqMsgId),
                                serializerProvider,
                                HeaderDesc.create(HeaderType.TYPE)
                        ),
                        new FixServerTest.LoggerPublish()));

        fixClient.connect();

        final FixServerTest.TestUserClientLogonSession testUserClientLogonSession = completableFuture.get(10, TimeUnit.MILLISECONDS);

        final var priceListener = new FixServerTest.TestUserClientLogonSession.PriceListener() {

            List<String> prices = new ArrayList<>();
            CompletableFuture<List<String>> priceFuture;

            @Override
            public void priceChanged(String str, String p) {
                log.info("EUR price changed: " + str + " " + p);
                prices.add(p);
                if (prices.size() == 10) {
                    prices = new ArrayList<>();
                    if (priceFuture != null) {
                        priceFuture.complete(prices);
                        priceFuture = null;
                    }
                }
            }
        };
        priceListener.priceFuture = new CompletableFuture<>();
        testUserClientLogonSession.subscribe("EUR", priceListener);

        System.out.println("Client.main " + priceListener.priceFuture.get(10, TimeUnit.SECONDS));

        fixClient.disconnect();
        priceListener.priceFuture = new CompletableFuture<>();

        fixClient.connect();

        System.out.println("Client.main " + priceListener.priceFuture.get(10, TimeUnit.SECONDS));

        fixClient.disconnect();
    }
}
