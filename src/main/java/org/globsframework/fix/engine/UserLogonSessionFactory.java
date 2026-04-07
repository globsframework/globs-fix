package org.globsframework.fix.engine;

public interface UserLogonSessionFactory {
    FixSessionImpl.UserLogonSession create(Shutdown shutdown);
}
