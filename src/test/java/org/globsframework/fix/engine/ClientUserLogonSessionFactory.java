package org.globsframework.fix.engine;

public class ClientUserLogonSessionFactory implements UserLogonSessionFactory {
    private final NotifyNewClient notifyNewClient;


    interface NotifyNewClient {
        void newClient(ClientLogonSession clientLogonSession);
    }

    ClientUserLogonSessionFactory(NotifyNewClient notifyNewClient) {
        this.notifyNewClient = notifyNewClient;
    }

    @Override
    public UserLogonSession create(Shutdown shutdown) {
        ClientLogonSession clientLogonSession =
                new ClientLogonSession(shutdown, "AF", "BNP");
        notifyNewClient.newClient(clientLogonSession);
        return clientLogonSession;
    }
}
