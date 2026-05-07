package org.globsframework.fix.engine;

public interface UserLogonSessionFactory {
    UserSession create(String senderCompId, String targetCompId, Shutdown shutdown);
}
