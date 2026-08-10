package org.globsframework.fix.fix44.app;

import org.globsframework.core.metamodel.GlobType;
import org.globsframework.core.metamodel.GlobTypeBuilder;
import org.globsframework.core.metamodel.GlobTypeBuilderFactory;
import org.globsframework.core.metamodel.fields.GlobArrayField;
import org.globsframework.core.metamodel.fields.IntegerField;
import org.globsframework.core.metamodel.fields.StringField;
import org.globsframework.fix.dictionary.model.FixFieldType;
import org.globsframework.fix.dictionary.model.FixMessageType;
import org.globsframework.fix.fix44.components.InstrumentType;

public class ExecutionReportType {
    public static final GlobType TYPE;
    public static final StringField orderID;
    public static final StringField clOrdID;
    public static final StringField quoteRespID;

    /* instrument */
    public static final StringField symbol;
    public static final StringField symbolSfx;
    public static final StringField securityID;
    public static final StringField securityIDSource;
    public static final GlobArrayField<InstrumentType.NoSecurityAltID> securityAltIDs;
    public static final IntegerField product;
    public static final StringField cfiCode;
    public static final StringField securityType;
    public static final StringField securitySubType;

    static {
        GlobTypeBuilder builder = GlobTypeBuilderFactory.create("ExecutionReport");
        builder.addAnnotation(FixMessageType.create("ExecutionReport"));
        orderID = builder.declareStringField("orderID", FixFieldType.create("OrderID"));
        clOrdID = builder.declareStringField("clOrdID", FixFieldType.create("ClOrdID"));
        quoteRespID = builder.declareStringField("quoteRespID", FixFieldType.create("QuoteRespID"));
        symbol = builder.declareStringField("symbol", FixFieldType.create("Symbol"));
        symbolSfx = builder.declareStringField("symbolSfx", FixFieldType.create("SymbolSfx"));
        securityID = builder.declareStringField("securityID", FixFieldType.create("SecurityID"));
        securityIDSource = builder.declareStringField("securityIDSource", FixFieldType.create("SecurityIDSource"));
        securityAltIDs = builder.declareGlobArrayField("securityAltIDs", () -> InstrumentType.NoSecurityAltID.TYPE);
        product = builder.declareIntegerField("product", FixFieldType.create("Product"));
        cfiCode = builder.declareStringField("cfiCode", FixFieldType.create("CFICode"));
        securityType = builder.declareStringField("securityType", FixFieldType.create("SecurityType"));
        securitySubType = builder.declareStringField("securitySubType", FixFieldType.create("SecuritySubType"));

        TYPE = builder.build();
    }
}
