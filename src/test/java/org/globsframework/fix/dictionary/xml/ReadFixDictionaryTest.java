package org.globsframework.fix.dictionary.xml;

import org.globsframework.fix.dictionary.*;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class ReadFixDictionaryTest {

    @Test
    void read() throws IOException {
        final FixModel fixModel = ReadFixDictionary.parse("fix44", () ->
                new InputStreamReader(getClass().getClassLoader().getResourceAsStream("FIX44.xml"),
                        StandardCharsets.UTF_8), new FieldFactoryImpl());
        final FixMessage heartbeat = fixModel.getMessage("Heartbeat");
        assertNotNull(heartbeat);
        assertEquals(1, heartbeat.getElements().size());
        final FixComponent settlInstructionsData = fixModel.getComponent("SettlInstructionsData");
        assertNotNull(settlInstructionsData);
        assertEquals(5, settlInstructionsData.getElements().size());
        final FixElement group = settlInstructionsData.getElements().get(4);
        assertNotNull(group);
        assertTrue(group instanceof FixGroup);
        final FixGroup fixGroup = (FixGroup) group;
        assertEquals(3, fixGroup.getElements().size());
        final FixElement SettlParties = fixGroup.getElements().get(2);
        assertNotNull(SettlParties);
        assertTrue(SettlParties instanceof FixComponent);
        final FixComponent fixComponent = (FixComponent) SettlParties;
        assertEquals(1, fixComponent.getElements().size());
        assertTrue(fixComponent.getElements().get(0) instanceof FixGroup);
    }
}