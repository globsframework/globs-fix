package org.globsframework.fix.fix44.app;

import org.globsframework.core.metamodel.GlobType;
import org.globsframework.core.metamodel.GlobTypeBuilder;
import org.globsframework.core.metamodel.GlobTypeBuilderFactory;
import org.globsframework.core.metamodel.fields.GlobArrayField;
import org.globsframework.core.metamodel.fields.GlobField;
import org.globsframework.core.metamodel.fields.IntegerField;
import org.globsframework.core.metamodel.fields.StringField;
import org.globsframework.fix.dictionary.model.FixFieldType;
import org.globsframework.fix.dictionary.model.FixGroupType;
import org.globsframework.fix.dictionary.model.FixMessageType;
import org.globsframework.fix.fix44.components.InstrumentType;
import org.globsframework.fix.fix44.components.OrderQtyDataType;
import org.globsframework.fix.fix44.components.PartiesType;

public class QuoteResponseType {
    public static final GlobType TYPE;

    public static final StringField quoteRespID;
    public static final StringField quoteID;
    public static final IntegerField quoteRespType;
    public static final StringField clOrdID;
    public static final StringField orderCapacity;
    public static final StringField ioiid;
    public static final IntegerField quoteType;
    public static final GlobArrayField quoteQualifiers;
    public static final GlobField parties;
    public static final StringField tradingSessionID;
    public static final StringField tradingSessionSubID;
    public static final GlobField instrument;
    // FinancingDetails skip
    public static final GlobArrayField underlyings;
    public static final StringField side;
    public static final GlobField orderQtyData;
    public static final StringField settlType;
    public static final StringField settlDate;
    public static final StringField settlDate2;
    public static final StringField orderQty2;
    public static final StringField currency;
    public static final GlobArrayField stipulations;
    public static final StringField account;
    public static final IntegerField acctIDSource;
    public static final IntegerField accountType;
    public static final GlobArrayField legs;
    public static final StringField bidPx;
    public static final StringField offerPx;
    public static final StringField mktBidPx;
    public static final StringField mktOfferPx;
    public static final StringField minBidSize;
    public static final StringField bidSize;
    public static final StringField minOfferSize;
    public static final StringField offerSize;
    public static final StringField validUntilTime;
    public static final StringField bidSpotRate;

    public static class NoUnderlyings {
        public static final GlobType TYPE;
        public static final GlobField underlyingInstrument;

        static {
            GlobTypeBuilder builder = GlobTypeBuilderFactory.create("QuoteRespNoUnderlyings");
            builder.addAnnotation(FixGroupType.create("NoUnderlyings"));
            underlyingInstrument = builder.declareGlobField("underlyingInstrument", () -> InstrumentType.TYPE);
            TYPE = builder.build();
        }
    }

    public static class NoLegs {
        public static final GlobType TYPE;
        public static final GlobField instrumentLeg;
        public static final StringField legQty;
        public static final IntegerField legSwapType;
        public static final StringField legSettlType;
        public static final StringField legSettlDate;
        public static final GlobArrayField legStipulations;
        public static final GlobField nestedParties;
        public static final IntegerField legPriceType;
        public static final StringField legBidPx;
        public static final StringField legOfferPx;
        public static final GlobField legBenchmarkCurveData;

        static {
            GlobTypeBuilder builder = GlobTypeBuilderFactory.create("QuoteRespNoLegs");
            builder.addAnnotation(FixGroupType.create("NoLegs"));
            instrumentLeg = builder.declareGlobField("instrumentLeg", () -> InstrumentType.TYPE);
            legQty = builder.declareStringField("legQty", FixFieldType.create("LegQty"));
            legSwapType = builder.declareIntegerField("legSwapType", FixFieldType.create("LegSwapType"));
            legSettlType = builder.declareStringField("legSettlType", FixFieldType.create("LegSettlType"));
            legSettlDate = builder.declareStringField("legSettlDate", FixFieldType.create("LegSettlDate"));
            legStipulations = builder.declareGlobArrayField("legStipulations", () -> QuoteRequestType.NoStipulations.TYPE);
            nestedParties = builder.declareGlobField("nestedParties", () -> PartiesType.TYPE);
            legPriceType = builder.declareIntegerField("legPriceType", FixFieldType.create("LegPriceType"));
            legBidPx = builder.declareStringField("legBidPx", FixFieldType.create("LegBidPx"));
            legOfferPx = builder.declareStringField("legOfferPx", FixFieldType.create("LegOfferPx"));
            legBenchmarkCurveData = builder.declareGlobField("legBenchmarkCurveData", () -> QuoteRequestType.LegBenchmarkCurveDataType.TYPE);
            TYPE = builder.build();
        }
    }

    static {
        GlobTypeBuilder builder = GlobTypeBuilderFactory.create("QuoteResponse");
        builder.addAnnotation(FixMessageType.create("QuoteResponse"));
        quoteRespID = builder.declareStringField("quoteRespID", FixFieldType.create("QuoteRespID"));
        quoteID = builder.declareStringField("quoteID", FixFieldType.create("QuoteID"));
        quoteRespType = builder.declareIntegerField("quoteRespType", FixFieldType.create("QuoteRespType"));
        clOrdID = builder.declareStringField("clOrdID", FixFieldType.create("ClOrdID"));
        orderCapacity = builder.declareStringField("orderCapacity", FixFieldType.create("OrderCapacity"));
        ioiid = builder.declareStringField("ioiid", FixFieldType.create("IOIID"));
        quoteType = builder.declareIntegerField("quoteType", FixFieldType.create("QuoteType"));
        quoteQualifiers = builder.declareGlobArrayField("quoteQualifiers", () -> QuoteRequestType.NoQuoteQualifiers.TYPE);
        parties = builder.declareGlobField("parties", () -> PartiesType.TYPE);
        tradingSessionID = builder.declareStringField("tradingSessionID", FixFieldType.create("TradingSessionID"));
        tradingSessionSubID = builder.declareStringField("tradingSessionSubID", FixFieldType.create("TradingSessionSubID"));
        instrument = builder.declareGlobField("instrument", () -> InstrumentType.TYPE);
        underlyings = builder.declareGlobArrayField("underlyings", () -> NoUnderlyings.TYPE);
        side = builder.declareStringField("side", FixFieldType.create("Side"));
        orderQtyData = builder.declareGlobField("orderQtyData", () -> OrderQtyDataType.TYPE);
        settlType = builder.declareStringField("settlType", FixFieldType.create("SettlType"));
        settlDate = builder.declareStringField("settlDate", FixFieldType.create("SettlDate"));
        settlDate2 = builder.declareStringField("settlDate2", FixFieldType.create("SettlDate2"));
        orderQty2 = builder.declareStringField("orderQty2", FixFieldType.create("OrderQty2"));
        currency = builder.declareStringField("currency", FixFieldType.create("Currency"));
        stipulations = builder.declareGlobArrayField("stipulations", () -> QuoteRequestType.NoStipulations.TYPE);
        account = builder.declareStringField("account", FixFieldType.create("Account"));
        acctIDSource = builder.declareIntegerField("acctIDSource", FixFieldType.create("AcctIDSource"));
        accountType = builder.declareIntegerField("accountType", FixFieldType.create("AccountType"));
        legs = builder.declareGlobArrayField("legs", () -> NoLegs.TYPE);
        bidPx = builder.declareStringField("bidPx", FixFieldType.create("BidPx"));
        offerPx = builder.declareStringField("offerPx", FixFieldType.create("OfferPx"));
        mktBidPx = builder.declareStringField("mktBidPx", FixFieldType.create("MktBidPx"));
        mktOfferPx = builder.declareStringField("mktOfferPx", FixFieldType.create("MktOfferPx"));
        minBidSize = builder.declareStringField("minBidSize", FixFieldType.create("MinBidSize"));
        bidSize = builder.declareStringField("bidSize", FixFieldType.create("BidSize"));
        minOfferSize = builder.declareStringField("minOfferSize", FixFieldType.create("MinOfferSize"));
        offerSize = builder.declareStringField("offerSize", FixFieldType.create("OfferSize"));
        validUntilTime = builder.declareStringField("validUntilTime", FixFieldType.create("ValidUntilTime"));
        bidSpotRate = builder.declareStringField("bidSpotRate", FixFieldType.create("BidSpotRate"));
        TYPE = builder.build();
    }
}
