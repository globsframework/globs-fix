package org.globsframework.fix.dictionary.reverter;

import org.globsframework.core.metamodel.GlobType;
import org.globsframework.core.metamodel.fields.Field;
import org.globsframework.fix.dictionary.FixModel;
import org.globsframework.fix.dictionary.model.FixMessageType;
import org.globsframework.fix.dictionary.xml.FieldFactoryImpl;
import org.globsframework.fix.dictionary.xml.ReadFixDictionary;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class FixModelToGlobTypeTest {

    @Test
    void checkCreateTypeFromDic() throws Exception {
        final FixModel fixModel = ReadFixDictionary.parse("fix44", () ->
                new InputStreamReader(getClass().getClassLoader().getResourceAsStream("FIX44.xml"),
                        StandardCharsets.UTF_8), new FieldFactoryImpl());
        final FixModelToGlobType.FixGlobType type = FixModelToGlobType.toType(fixModel);
        assertNotNull(type.header());
        assertNotNull(type.footer());
        assertNotEquals(0, type.messages().length);
        assertNotNull(type.header().getField("BeginString"));
        assertNotNull(type.header().getField("OrigSendingTime"));
        assertNotNull(type.header().getField("NoHops"));
        final Optional<GlobType> quoteRequestReject = Arrays.stream(type.messages()).filter(message -> message.getAnnotation(FixMessageType.UNIQUE_KEY)
                        .get(FixMessageType.name).equals("QuoteRequestReject"))
                .findFirst();
        assertTrue(quoteRequestReject.isPresent());
        assertEquals("QuoteRequestReject", quoteRequestReject.get().getName());
        assertNotNull(quoteRequestReject.get().getField("YieldCalcDate"));
        final Field noRelatedSym = quoteRequestReject.get()
                .getField("NoRelatedSym");
        assertNotNull(noRelatedSym);
        final Field noUnderlyings = noRelatedSym
                .asGlobArrayField()
                .getTargetType()
                .getField("NoUnderlyings");
        assertNotNull(noUnderlyings);
        final Field noUnderlyingSecurityAltID = noUnderlyings
                .asGlobArrayField()
                .getTargetType().getField("NoUnderlyingSecurityAltID");
        assertNotNull(noUnderlyingSecurityAltID);
        assertNotNull(noUnderlyingSecurityAltID
                .asGlobArrayField().getTargetType()
                .getField("UnderlyingSecurityAltID"));
    }
}