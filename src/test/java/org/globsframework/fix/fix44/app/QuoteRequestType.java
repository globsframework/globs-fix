package org.globsframework.fix.fix44.app;

import org.globsframework.core.metamodel.GlobType;
import org.globsframework.core.metamodel.GlobTypeBuilder;
import org.globsframework.core.metamodel.GlobTypeBuilderFactory;
import org.globsframework.core.metamodel.fields.GlobArrayField;
import org.globsframework.core.metamodel.fields.GlobField;
import org.globsframework.core.metamodel.fields.IntegerField;
import org.globsframework.core.metamodel.fields.StringField;
import org.globsframework.core.model.MutableGlob;
import org.globsframework.fix.dictionary.model.FixComponentType;
import org.globsframework.fix.dictionary.model.FixFieldType;
import org.globsframework.fix.dictionary.model.FixGroupType;
import org.globsframework.fix.dictionary.model.FixMessageType;
import org.globsframework.fix.fix44.components.InstrumentType;
import org.globsframework.fix.fix44.components.OrderQtyDataType;
import org.globsframework.fix.fix44.components.PartiesType;

public class QuoteRequestType {
    public static final GlobType TYPE;

    public static final StringField quoteReqID;
    public static final StringField rfqReqID;
    public static final StringField clOrdID;
    public static final StringField orderCapacity;
    public static final GlobArrayField relatedSyms;
    public static final StringField text;
    public static final IntegerField encodedTextLen;
    public static final StringField encodedText;

    public static MutableGlob create(String quoteId) {
        return TYPE.instantiate().set(quoteReqID, quoteId);
    }

    public static class NoRelatedSym {
        public static final GlobType TYPE;
        public static final GlobField<InstrumentType> instrument;
        // FinancingDetails component - skipped for now if not used elsewhere or very complex
        public static final GlobArrayField<NoUnderlyings> underlyings;
        public static final StringField prevClosePx;
        public static final IntegerField quoteRequestType;
        public static final IntegerField quoteType;
        public static final StringField tradingSessionID;
        public static final StringField tradingSessionSubID;
        public static final StringField tradeOriginationDate;
        public static final StringField side;
        public static final IntegerField qtyType;
        public static final GlobField<OrderQtyDataType> orderQtyData;
        public static final StringField settlType;
        public static final StringField settlDate;
        public static final StringField settlDate2;
        public static final StringField orderQty2;
        public static final StringField currency;
        public static final GlobArrayField<NoStipulations> stipulations;
        public static final StringField account;
        public static final IntegerField acctIDSource;
        public static final IntegerField accountType;
        public static final GlobArrayField<NoLegs> legs;
        public static final GlobArrayField<NoQuoteQualifiers> quoteQualifiers;
        public static final IntegerField quotePriceType;
        public static final StringField ordType;
        public static final StringField validUntilTime;
        public static final StringField expireTime;
        public static final StringField transactTime;
        public static final GlobField<?> spreadOrBenchmarkCurveData;
        public static final IntegerField priceType;
        public static final StringField price;
        public static final StringField price2;
        public static final GlobField<YieldDataType> yieldData;
        public static final GlobField<PartiesType> parties;

        static {
            GlobTypeBuilder builder = GlobTypeBuilderFactory.create("NoRelatedSym");
            builder.addAnnotation(FixGroupType.create("NoRelatedSym"));
            instrument = builder.declareGlobField("instrument", () -> InstrumentType.TYPE);
            underlyings = builder.declareGlobArrayField("underlyings", () -> NoUnderlyings.TYPE);
            prevClosePx = builder.declareStringField("prevClosePx", FixFieldType.create("PrevClosePx"));
            quoteRequestType = builder.declareIntegerField("quoteRequestType", FixFieldType.create("QuoteRequestType"));
            quoteType = builder.declareIntegerField("quoteType", FixFieldType.create("QuoteType"));
            tradingSessionID = builder.declareStringField("tradingSessionID", FixFieldType.create("TradingSessionID"));
            tradingSessionSubID = builder.declareStringField("tradingSessionSubID", FixFieldType.create("TradingSessionSubID"));
            tradeOriginationDate = builder.declareStringField("tradeOriginationDate", FixFieldType.create("TradeOriginationDate"));
            side = builder.declareStringField("side", FixFieldType.create("Side"));
            qtyType = builder.declareIntegerField("qtyType", FixFieldType.create("QtyType"));
            orderQtyData = builder.declareGlobField("orderQtyData", () -> OrderQtyDataType.TYPE);
            settlType = builder.declareStringField("settlType", FixFieldType.create("SettlType"));
            settlDate = builder.declareStringField("settlDate", FixFieldType.create("SettlDate"));
            settlDate2 = builder.declareStringField("settlDate2", FixFieldType.create("SettlDate2"));
            orderQty2 = builder.declareStringField("orderQty2", FixFieldType.create("OrderQty2"));
            currency = builder.declareStringField("currency", FixFieldType.create("Currency"));
            stipulations = builder.declareGlobArrayField("stipulations", () -> NoStipulations.TYPE);
            account = builder.declareStringField("account", FixFieldType.create("Account"));
            acctIDSource = builder.declareIntegerField("acctIDSource", FixFieldType.create("AcctIDSource"));
            accountType = builder.declareIntegerField("accountType", FixFieldType.create("AccountType"));
            legs = builder.declareGlobArrayField("legs", () -> NoLegs.TYPE);
            quoteQualifiers = builder.declareGlobArrayField("quoteQualifiers", () -> NoQuoteQualifiers.TYPE);
            quotePriceType = builder.declareIntegerField("quotePriceType", FixFieldType.create("QuotePriceType"));
            ordType = builder.declareStringField("ordType", FixFieldType.create("OrdType"));
            validUntilTime = builder.declareStringField("validUntilTime", FixFieldType.create("ValidUntilTime"));
            expireTime = builder.declareStringField("expireTime", FixFieldType.create("ExpireTime"));
            transactTime = builder.declareStringField("transactTime", FixFieldType.create("TransactTime"));
            spreadOrBenchmarkCurveData = builder.declareGlobField("spreadOrBenchmarkCurveData", () -> SpreadOrBenchmarkCurveDataType.TYPE);
            priceType = builder.declareIntegerField("priceType", FixFieldType.create("PriceType"));
            price = builder.declareStringField("price", FixFieldType.create("Price"));
            price2 = builder.declareStringField("price2", FixFieldType.create("Price2"));
            yieldData = builder.declareGlobField("yieldData", () -> YieldDataType.TYPE);
            parties = builder.declareGlobField("parties", () -> PartiesType.TYPE);
            TYPE = builder.build();
        }
    }

    public static class NoUnderlyings {
        public static final GlobType TYPE;
        public static final GlobField<InstrumentType> underlyingInstrument;

        static {
            GlobTypeBuilder builder = GlobTypeBuilderFactory.create("NoUnderlyings");
            builder.addAnnotation(FixGroupType.create("NoUnderlyings"));
            underlyingInstrument = builder.declareGlobField("underlyingInstrument", () -> InstrumentType.TYPE); // Should ideally be UnderlyingInstrumentType
            TYPE = builder.build();
        }
    }

    public static class NoStipulations {
        public static final GlobType TYPE;
        public static final StringField stipulationType;
        public static final StringField stipulationValue;

        static {
            GlobTypeBuilder builder = GlobTypeBuilderFactory.create("NoStipulations");
            builder.addAnnotation(FixGroupType.create("NoStipulations"));
            stipulationType = builder.declareStringField("stipulationType", FixFieldType.create("StipulationType"));
            stipulationValue = builder.declareStringField("stipulationValue", FixFieldType.create("StipulationValue"));
            TYPE = builder.build();
        }
    }

    public static class NoQuoteQualifiers {
        public static final GlobType TYPE;
        public static final StringField quoteQualifier;

        static {
            GlobTypeBuilder builder = GlobTypeBuilderFactory.create("NoQuoteQualifiers");
            builder.addAnnotation(FixGroupType.create("NoQuoteQualifiers"));
            quoteQualifier = builder.declareStringField("quoteQualifier", FixFieldType.create("QuoteQualifier"));
            TYPE = builder.build();
        }
    }

    public static class NoLegs {
        public static final GlobType TYPE;
        public static final GlobField<InstrumentType> instrumentLeg;
        public static final StringField legQty;
        public static final IntegerField legSwapType;
        public static final StringField legSettlType;
        public static final StringField legSettlDate;
        public static final GlobArrayField<NoStipulations> legStipulations;
        public static final GlobField<PartiesType> nestedParties;
        public static final GlobField<LegBenchmarkCurveDataType> legBenchmarkCurveData;

        static {
            GlobTypeBuilder builder = GlobTypeBuilderFactory.create("NoLegs");
            builder.addAnnotation(FixGroupType.create("NoLegs"));
            instrumentLeg = builder.declareGlobField("instrumentLeg", () -> InstrumentType.TYPE); // Should be InstrumentLegType
            legQty = builder.declareStringField("legQty", FixFieldType.create("LegQty"));
            legSwapType = builder.declareIntegerField("legSwapType", FixFieldType.create("LegSwapType"));
            legSettlType = builder.declareStringField("legSettlType", FixFieldType.create("LegSettlType"));
            legSettlDate = builder.declareStringField("legSettlDate", FixFieldType.create("LegSettlDate"));
            legStipulations = builder.declareGlobArrayField("legStipulations", () -> NoStipulations.TYPE);
            nestedParties = builder.declareGlobField("nestedParties", () -> PartiesType.TYPE); // Should be NestedParties
            legBenchmarkCurveData = builder.declareGlobField("legBenchmarkCurveData", () -> LegBenchmarkCurveDataType.TYPE);
            TYPE = builder.build();
        }
    }

    public static class SpreadOrBenchmarkCurveDataType {
        public static final GlobType TYPE;

        static {
            GlobTypeBuilder builder = GlobTypeBuilderFactory.create("SpreadOrBenchmarkCurveData");
            builder.addAnnotation(FixComponentType.create("SpreadOrBenchmarkCurveData"));
            TYPE = builder.build();
        }
    }

    public static class YieldDataType {
        public static final GlobType TYPE;

        static {
            GlobTypeBuilder builder = GlobTypeBuilderFactory.create("YieldData");
            builder.addAnnotation(FixComponentType.create("YieldData"));
            TYPE = builder.build();
        }
    }

    public static class LegBenchmarkCurveDataType {
        public static final GlobType TYPE;

        static {
            GlobTypeBuilder builder = GlobTypeBuilderFactory.create("LegBenchmarkCurveData");
            builder.addAnnotation(FixComponentType.create("LegBenchmarkCurveData"));
            TYPE = builder.build();
        }
    }

    static {
        GlobTypeBuilder builder = GlobTypeBuilderFactory.create("QuoteRequest");
        builder.addAnnotation(FixMessageType.create("QuoteRequest"));
        quoteReqID = builder.declareStringField("quoteReqID", FixFieldType.create("QuoteReqID"));
        rfqReqID = builder.declareStringField("rfqReqID", FixFieldType.create("RFQReqID"));
        clOrdID = builder.declareStringField("clOrdID", FixFieldType.create("ClOrdID"));
        orderCapacity = builder.declareStringField("orderCapacity", FixFieldType.create("OrderCapacity"));
        relatedSyms = builder.declareGlobArrayField("relatedSyms", () -> NoRelatedSym.TYPE);
        text = builder.declareStringField("text", FixFieldType.create("Text"));
        encodedTextLen = builder.declareIntegerField("encodedTextLen", FixFieldType.create("EncodedTextLen"));
        encodedText = builder.declareStringField("encodedText", FixFieldType.create("EncodedText"));
        TYPE = builder.build();
    }
}
