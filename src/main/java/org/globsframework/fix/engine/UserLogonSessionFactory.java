package org.globsframework.fix.engine;

public interface UserLogonSessionFactory {
    UserLogonSession create(Shutdown shutdown);
}
