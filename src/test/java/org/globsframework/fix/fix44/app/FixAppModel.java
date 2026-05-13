package org.globsframework.fix.fix44.app;

import org.globsframework.core.metamodel.GlobModel;
import org.globsframework.core.metamodel.GlobType;
import org.globsframework.core.metamodel.impl.DefaultGlobModel;

import java.util.HashSet;
import java.util.Set;

public class FixAppModel {
    public static final GlobModel MODEL = new DefaultGlobModel(QuoteRequestType.TYPE, QuoteResponseType.TYPE,
            NewOrderSingleType.TYPE, ExecutionReportType.TYPE);
    public static final Set<GlobType> TYPES = new HashSet<>(MODEL.getAll());
}
