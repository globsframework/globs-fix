package org.globsframework.fix.engine;

import org.globsframework.core.metamodel.GlobModel;
import org.globsframework.core.metamodel.impl.DefaultGlobModel;
import org.globsframework.fix.HeaderType;
import org.globsframework.fix.TrailerType;
import org.globsframework.fix.UTCFormater;
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

        final ExecutorService executorService = Executors.newCachedThreadPool();
        final ScheduledExecutorService scheduledExecutorService = Executors.newSingleThreadScheduledExecutor();

        final var notifyNewClient = new ClientUserLogonSessionFactory.NotifyNewClient(){
            CompletableFuture<ClientUserSession> completableFuture;

            @Override
            public void newClient(ClientUserSession clientLogonSession) {
                completableFuture.complete(clientLogonSession);
            }
        };
        final ClientUserLogonSessionFactory userLogonSessionFactory = new ClientUserLogonSessionFactory(notifyNewClient);
        final FixModel fixModel = ReadFixDictionary.parse("fix44", () ->
                new InputStreamReader(FixServer.class.getClassLoader().getResourceAsStream("FIX44.xml"),
                        StandardCharsets.UTF_8), new FieldFactoryImpl());

        final GlobModel globModel = new DefaultGlobModel(QuoteRequestType.TYPE, QuoteResponseType.TYPE);

        final SingleSerializerProvider serializerProvider = new SingleSerializerProvider(
                DeserializerFixReaderBuilder.create(fixModel, globModel, HeaderType.TYPE, TrailerType.TYPE),
                SerializerFixWriterBuilder.create(fixModel, globModel, HeaderType.TYPE, TrailerType.TYPE,
                        UTCFormater.withAutoRefresh(scheduledExecutorService)),
                HeaderType.getHeaderDesc());

        final FixClient fixClient = new FixClient("localhost", 5456,
                new FixConnectionFactory(
                        new FixServerTest.LoggerPublish(), executorService, scheduledExecutorService, userLogonSessionFactory, (senderCompID, targetCompID) -> new NoCacheDataAdapt(),
                        serializerProvider, HeaderDesc.create(HeaderType.TYPE)));

        notifyNewClient.completableFuture = new CompletableFuture<>();
        CompletableFuture<FixLogout> fixLogoutCompletableFuture =
                fixClient.connectAsInitiator("AF", "BNP");

        ClientUserSession clientLogonSession = notifyNewClient.completableFuture.get(10, TimeUnit.MILLISECONDS);

        final var priceListener = new ClientLogonSession.PriceListener() {

            List<String> prices = new ArrayList<>();
            CompletableFuture<List<String>> priceFuture;

            @Override
            public void priceChanged(String str, String p) {
                log.info("EUR price changed: " + str + " " + p);
                prices.add(p);
                if (prices.size() == 1000) {
                    if (priceFuture != null) {
                        priceFuture.complete(prices);
                        priceFuture = null;
                    }
                    prices = new ArrayList<>();
                }
            }
        };
        priceListener.priceFuture = new CompletableFuture<>();
        clientLogonSession.subscribe("EUR", priceListener);

        System.out.println("Client.main " + priceListener.priceFuture.get(10, TimeUnit.SECONDS));

        fixLogoutCompletableFuture.resultNow().close();

        notifyNewClient.completableFuture = new CompletableFuture<>();
        fixLogoutCompletableFuture = fixClient.connectAsInitiator("AF", "BNP");
        clientLogonSession = notifyNewClient.completableFuture.get(10, TimeUnit.MILLISECONDS);

        priceListener.priceFuture = new CompletableFuture<>();

        clientLogonSession.subscribe("EUR", priceListener);


        System.out.println("Client.main " + priceListener.priceFuture.get(10, TimeUnit.SECONDS));

        fixLogoutCompletableFuture.resultNow().close();
        executorService.shutdown();
        scheduledExecutorService.shutdownNow(); 
        executorService.awaitTermination(100, TimeUnit.SECONDS);
        scheduledExecutorService.awaitTermination(100, TimeUnit.SECONDS);
    }
}
