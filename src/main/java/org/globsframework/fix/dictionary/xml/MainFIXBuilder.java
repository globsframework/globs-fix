package org.globsframework.fix.dictionary.xml;

import org.globsframework.fix.dictionary.FixModel;

public interface MainFIXBuilder {
    FieldContainerBuilder declareHeader();

    FieldContainerBuilder declareTrailer();

    FixModel complete(String version);
}
