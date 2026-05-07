package org.globsframework.fix.engine;

public class ClientUserLogonSessionFactory implements UserLogonSessionFactory {
    private final NotifyNewClient notifyNewClient;

    interface NotifyNewClient {
        void newClient(ClientUserSession clientLogonSession);
    }

    ClientUserLogonSessionFactory(NotifyNewClient notifyNewClient) {
        this.notifyNewClient = notifyNewClient;
    }

    @Override
    public UserSession create(String senderCompId, String targetCompId, Shutdown shutdown) {
        ClientUserSession clientUserSession =
                new ClientUserSession(senderCompId, targetCompId, shutdown);
        notifyNewClient.newClient(clientUserSession);
        return clientUserSession;
    }
}
