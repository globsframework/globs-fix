package org.globsframework.fix.fix44.components;

import org.globsframework.core.metamodel.GlobType;
import org.globsframework.core.metamodel.GlobTypeBuilder;
import org.globsframework.core.metamodel.GlobTypeBuilderFactory;
import org.globsframework.core.metamodel.fields.StringField;
import org.globsframework.fix.dictionary.model.FixComponentType;
import org.globsframework.fix.dictionary.model.FixFieldType;

public class OrderQtyDataType {
    public static final GlobType TYPE;

    public static final StringField orderQty;
    public static final StringField cashOrderQty;
    public static final StringField orderPercent;
    public static final StringField roundingDirection;
    public static final StringField roundingModulus;

    static {
        GlobTypeBuilder builder = GlobTypeBuilderFactory.create("OrderQtyData");
        builder.addAnnotation(FixComponentType.create("OrderQtyData"));
        orderQty = builder.declareStringField("orderQty", FixFieldType.create("OrderQty"));
        cashOrderQty = builder.declareStringField("cashOrderQty", FixFieldType.create("CashOrderQty"));
        orderPercent = builder.declareStringField("orderPercent", FixFieldType.create("OrderPercent"));
        roundingDirection = builder.declareStringField("roundingDirection", FixFieldType.create("RoundingDirection"));
        roundingModulus = builder.declareStringField("roundingModulus", FixFieldType.create("RoundingModulus"));
        TYPE = builder.build();
    }
}
