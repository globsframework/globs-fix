package org.globsframework.fix.fix44.components;

import org.globsframework.core.metamodel.GlobType;
import org.globsframework.core.metamodel.GlobTypeBuilder;
import org.globsframework.core.metamodel.GlobTypeBuilderFactory;
import org.globsframework.core.metamodel.fields.GlobArrayField;
import org.globsframework.core.metamodel.fields.IntegerField;
import org.globsframework.core.metamodel.fields.StringField;
import org.globsframework.fix.dictionary.model.FixComponentType;
import org.globsframework.fix.dictionary.model.FixFieldType;
import org.globsframework.fix.dictionary.model.FixGroupType;

public class InstrumentType {
    public static final GlobType TYPE;

    public static final StringField symbol;
    public static final StringField symbolSfx;
    public static final StringField securityID;
    public static final StringField securityIDSource;
    public static final GlobArrayField securityAltIDs;
    public static final IntegerField product;
    public static final StringField cfiCode;
    public static final StringField securityType;
    public static final StringField securitySubType;
    public static final StringField maturityMonthYear;
    public static final StringField maturityDate;
    public static final IntegerField putOrCall;
    public static final StringField couponPaymentDate;
    public static final StringField issueDate;
    public static final StringField repoCollateralSecurityType;
    public static final IntegerField repurchaseTerm;
    public static final StringField repurchaseRate;
    public static final StringField factor;
    public static final StringField creditRating;
    public static final StringField instrRegistry;
    public static final StringField countryOfIssue;
    public static final StringField stateOrProvinceOfIssue;
    public static final StringField localeOfIssue;
    public static final StringField redemptionDate;
    public static final StringField strikePrice;
    public static final StringField strikeCurrency;
    public static final StringField optAttribute;
    public static final StringField contractMultiplier;
    public static final StringField couponRate;
    public static final StringField securityExchange;
    public static final StringField issuer;
    public static final IntegerField encodedIssuerLen;
    public static final StringField encodedIssuer;
    public static final StringField securityDesc;
    public static final IntegerField encodedSecurityDescLen;
    public static final StringField encodedSecurityDesc;
    public static final StringField pool;
    public static final StringField contractSettlMonth;
    public static final StringField cpProgram;
    public static final StringField cpRegType;
    public static final GlobArrayField events;
    public static final StringField datedDate;
    public static final StringField interestAccrualDate;

    public static class NoSecurityAltID {
        public static final GlobType TYPE;
        public static final StringField securityAltID;
        public static final StringField securityAltIDSource;

        static {
            GlobTypeBuilder builder = GlobTypeBuilderFactory.create("NoSecurityAltID");
            builder.addAnnotation(FixGroupType.create("NoSecurityAltID"));
            securityAltID = builder.declareStringField("securityAltID", FixFieldType.create("SecurityAltID"));
            securityAltIDSource = builder.declareStringField("securityAltIDSource", FixFieldType.create("SecurityAltIDSource"));
            TYPE = builder.build();
        }
    }

    public static class NoEvents {
        public static final GlobType TYPE;
        public static final IntegerField eventType;
        public static final StringField eventDate;
        public static final StringField eventPx;
        public static final StringField eventText;

        static {
            GlobTypeBuilder builder = GlobTypeBuilderFactory.create("NoEvents");
            builder.addAnnotation(FixGroupType.create("NoEvents"));
            eventType = builder.declareIntegerField("eventType", FixFieldType.create("EventType"));
            eventDate = builder.declareStringField("eventDate", FixFieldType.create("EventDate"));
            eventPx = builder.declareStringField("eventPx", FixFieldType.create("EventPx"));
            eventText = builder.declareStringField("eventText", FixFieldType.create("EventText"));
            TYPE = builder.build();
        }
    }

    static {
        GlobTypeBuilder builder = GlobTypeBuilderFactory.create("Instrument");
        builder.addAnnotation(FixComponentType.create("Instrument"));
        symbol = builder.declareStringField("symbol", FixFieldType.create("Symbol"));
        symbolSfx = builder.declareStringField("symbolSfx", FixFieldType.create("SymbolSfx"));
        securityID = builder.declareStringField("securityID", FixFieldType.create("SecurityID"));
        securityIDSource = builder.declareStringField("securityIDSource", FixFieldType.create("SecurityIDSource"));
        securityAltIDs = builder.declareGlobArrayField("securityAltIDs", () -> NoSecurityAltID.TYPE);
        product = builder.declareIntegerField("product", FixFieldType.create("Product"));
        cfiCode = builder.declareStringField("cfiCode", FixFieldType.create("CFICode"));
        securityType = builder.declareStringField("securityType", FixFieldType.create("SecurityType"));
        securitySubType = builder.declareStringField("securitySubType", FixFieldType.create("SecuritySubType"));
        maturityMonthYear = builder.declareStringField("maturityMonthYear", FixFieldType.create("MaturityMonthYear"));
        maturityDate = builder.declareStringField("maturityDate", FixFieldType.create("MaturityDate"));
        putOrCall = builder.declareIntegerField("putOrCall", FixFieldType.create("PutOrCall"));
        couponPaymentDate = builder.declareStringField("couponPaymentDate", FixFieldType.create("CouponPaymentDate"));
        issueDate = builder.declareStringField("issueDate", FixFieldType.create("IssueDate"));
        repoCollateralSecurityType = builder.declareStringField("repoCollateralSecurityType", FixFieldType.create("RepoCollateralSecurityType"));
        repurchaseTerm = builder.declareIntegerField("repurchaseTerm", FixFieldType.create("RepurchaseTerm"));
        repurchaseRate = builder.declareStringField("repurchaseRate", FixFieldType.create("RepurchaseRate"));
        factor = builder.declareStringField("factor", FixFieldType.create("Factor"));
        creditRating = builder.declareStringField("creditRating", FixFieldType.create("CreditRating"));
        instrRegistry = builder.declareStringField("instrRegistry", FixFieldType.create("InstrRegistry"));
        countryOfIssue = builder.declareStringField("countryOfIssue", FixFieldType.create("CountryOfIssue"));
        stateOrProvinceOfIssue = builder.declareStringField("stateOrProvinceOfIssue", FixFieldType.create("StateOrProvinceOfIssue"));
        localeOfIssue = builder.declareStringField("localeOfIssue", FixFieldType.create("LocaleOfIssue"));
        redemptionDate = builder.declareStringField("redemptionDate", FixFieldType.create("RedemptionDate"));
        strikePrice = builder.declareStringField("strikePrice", FixFieldType.create("StrikePrice"));
        strikeCurrency = builder.declareStringField("strikeCurrency", FixFieldType.create("StrikeCurrency"));
        optAttribute = builder.declareStringField("optAttribute", FixFieldType.create("OptAttribute"));
        contractMultiplier = builder.declareStringField("contractMultiplier", FixFieldType.create("ContractMultiplier"));
        couponRate = builder.declareStringField("couponRate", FixFieldType.create("CouponRate"));
        securityExchange = builder.declareStringField("securityExchange", FixFieldType.create("SecurityExchange"));
        issuer = builder.declareStringField("issuer", FixFieldType.create("Issuer"));
        encodedIssuerLen = builder.declareIntegerField("encodedIssuerLen", FixFieldType.create("EncodedIssuerLen"));
        encodedIssuer = builder.declareStringField("encodedIssuer", FixFieldType.create("EncodedIssuer"));
        securityDesc = builder.declareStringField("securityDesc", FixFieldType.create("SecurityDesc"));
        encodedSecurityDescLen = builder.declareIntegerField("encodedSecurityDescLen", FixFieldType.create("EncodedSecurityDescLen"));
        encodedSecurityDesc = builder.declareStringField("encodedSecurityDesc", FixFieldType.create("EncodedSecurityDesc"));
        pool = builder.declareStringField("pool", FixFieldType.create("Pool"));
        contractSettlMonth = builder.declareStringField("contractSettlMonth", FixFieldType.create("ContractSettlMonth"));
        cpProgram = builder.declareStringField("cpProgram", FixFieldType.create("CPProgram"));
        cpRegType = builder.declareStringField("cpRegType", FixFieldType.create("CPRegType"));
        events = builder.declareGlobArrayField("events", () -> NoEvents.TYPE);
        datedDate = builder.declareStringField("datedDate", FixFieldType.create("DatedDate"));
        interestAccrualDate = builder.declareStringField("interestAccrualDate", FixFieldType.create("InterestAccrualDate"));
        TYPE = builder.build();
    }
}
