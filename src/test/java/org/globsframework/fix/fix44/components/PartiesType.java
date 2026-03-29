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

public class PartiesType {
    public static final GlobType TYPE;

    public static final GlobArrayField partyIDs;

    public static class NoPartyIDs {
        public static final GlobType TYPE;
        public static final StringField partyID;
        public static final StringField partyIDSource;
        public static final IntegerField partyRole;
        public static final GlobArrayField partySubIDs;

        static {
            GlobTypeBuilder builder = GlobTypeBuilderFactory.create("NoPartyIDs");
            builder.addAnnotation(FixGroupType.create("NoPartyIDs"));
            partyID = builder.declareStringField("partyID", FixFieldType.create("PartyID"));
            partyIDSource = builder.declareStringField("partyIDSource", FixFieldType.create("PartyIDSource"));
            partyRole = builder.declareIntegerField("partyRole", FixFieldType.create("PartyRole"));
            partySubIDs = builder.declareGlobArrayField("partySubIDs", () -> NoPartySubIDs.TYPE);
            TYPE = builder.build();
        }
    }

    public static class NoPartySubIDs {
        public static final GlobType TYPE;
        public static final StringField partySubID;
        public static final IntegerField partySubIDType;

        static {
            GlobTypeBuilder builder = GlobTypeBuilderFactory.create("NoPartySubIDs");
            builder.addAnnotation(FixGroupType.create("NoPartySubIDs"));
            partySubID = builder.declareStringField("partySubID", FixFieldType.create("PartySubID"));
            partySubIDType = builder.declareIntegerField("partySubIDType", FixFieldType.create("PartySubIDType"));
            TYPE = builder.build();
        }
    }

    static {
        GlobTypeBuilder builder = GlobTypeBuilderFactory.create("Parties");
        builder.addAnnotation(FixComponentType.create("Parties"));
        partyIDs = builder.declareGlobArrayField("partyIDs", () -> NoPartyIDs.TYPE);
        TYPE = builder.build();
    }
}
