package org.globsframework.fix.engine;

import org.globsframework.fix.serializer.FixWriter;

public interface UserLogonSessionFactory {
    FixSessionImpl.UserLogonSession create(FixWriter writer, Shutdown shutdown);
}
