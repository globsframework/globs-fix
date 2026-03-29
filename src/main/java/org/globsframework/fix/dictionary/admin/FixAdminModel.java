package org.globsframework.fix.dictionary.admin;

import org.globsframework.core.metamodel.GlobModel;
import org.globsframework.core.metamodel.GlobType;
import org.globsframework.core.metamodel.impl.DefaultGlobModel;

import java.util.HashSet;
import java.util.Set;

public class FixAdminModel {
    public static final GlobModel MODEL = new DefaultGlobModel(HeartbeatType.TYPE,
            LogonType.TYPE, LogoutType.TYPE, ResendRequestType.TYPE, SequenceResetType.TYPE, TestRequestType.TYPE,
            RejectType.TYPE);
    public static final Set<GlobType> TYPES = new HashSet<>(MODEL.getAll());
}
