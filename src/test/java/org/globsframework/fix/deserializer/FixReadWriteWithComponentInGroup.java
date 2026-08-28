package org.globsframework.fix.deserializer;

import org.globsframework.core.metamodel.GlobModel;
import org.globsframework.core.metamodel.GlobType;
import org.globsframework.core.metamodel.GlobTypeBuilder;
import org.globsframework.core.metamodel.GlobTypeBuilderFactory;
import org.globsframework.core.metamodel.fields.GlobArrayField;
import org.globsframework.core.metamodel.fields.StringField;
import org.globsframework.core.metamodel.impl.DefaultGlobModel;
import org.globsframework.core.model.Glob;
import org.globsframework.core.model.MutableGlob;
import org.globsframework.fix.HeaderType;
import org.globsframework.fix.TrailerType;
import org.globsframework.fix.FormatDateTime;
import org.globsframework.fix.dictionary.FixModel;
import org.globsframework.fix.dictionary.model.FixFieldType;
import org.globsframework.fix.dictionary.model.FixGroupType;
import org.globsframework.fix.dictionary.model.FixMessageType;
import org.globsframework.fix.dictionary.xml.FieldFactoryImpl;
import org.globsframework.fix.dictionary.xml.ReadFixDictionary;
import org.globsframework.fix.serializer.FixWriter;
import org.globsframework.fix.serializer.Publish;
import org.globsframework.fix.serializer.SerializerFixWriterBuilder;
import org.globsframework.json.GSonUtils;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.DrbgParameters;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class FixReadWriteWithComponentInGroup {

    @Test
    void componentInGroup() throws IOException {
        final FixModel fixModel = ReadFixDictionary.parse("fix44", () ->
                new InputStreamReader(getClass().getClassLoader().getResourceAsStream("FIX44.xml"),
                        StandardCharsets.UTF_8), new FieldFactoryImpl());

        final GlobModel globModel = new DefaultGlobModel(PartialNews.TYPE);
        final SerializerFixWriterBuilder fixWriterBuilder = SerializerFixWriterBuilder.create(fixModel, globModel,
                HeaderType.TYPE, TrailerType.TYPE, FormatDateTime.shouldRefreshUTC());

        List<byte[]> datas = new ArrayList<>();
        final FixWriter writer = fixWriterBuilder.createWriter(new Publish() {
            @Override
            public void publish(byte[] data, int offset, int length) {
                datas.add(Arrays.copyOfRange(data, offset, offset + length));
            }
        }, new BasicMsgSeqProvider());

        MutableGlob news = PartialNews.create(PartialNews.GrpInstrument.create(
                        PartialNews.SecurityAltType.create("s1"),
                                PartialNews.SecurityAltType.create("s2")),
                PartialNews.GrpInstrument.create(PartialNews.SecurityAltType.create("s3"),
                                PartialNews.SecurityAltType.create("s4")));

        writer.write(HeaderType.create("AA", "BB"), news, null, false);

        assertEquals(1, datas.size());

        final FixReaderBuilder fixReaderBuilder = DeserializerFixReaderBuilder.create(fixModel, globModel,
                HeaderType.TYPE, null);
        final FixReader reader = fixReaderBuilder.createReader(new ByteArrayInputStream(datas.get(0))::read);
        final FixMessageValue read = reader.read();
        assertNotNull(read);
        final Glob message = read.message();
        final String json = GSonUtils.encode(message);
        assertEquals(GSonUtils.normalize("""
                {
                  "relatedInstr": [
                    {
                        "securityAltID": [
                          {
                            "SecurityAltID": "s1"
                          },
                          {
                            "SecurityAltID": "s2"
                          }
                        ]
                    },
                    {
                        "securityAltID": [
                          {
                            "SecurityAltID": "s3"
                          },
                          {
                            "SecurityAltID": "s4"
                          }
                        ]
                    }
                  ]
                }"""), GSonUtils.normalize(json));
    }

    public static class PartialNews {
        public static final GlobType TYPE;

        public static final GlobArrayField<DrbgParameters> relatedInstr;

        public static MutableGlob create(Glob... related) {
            return TYPE.instantiate()
                    .set(relatedInstr, related);
        }

        static {
            final GlobTypeBuilder typeBuilder = GlobTypeBuilderFactory.create("PartialNews");
            typeBuilder.addAnnotation(FixMessageType.create("News"));
            relatedInstr = typeBuilder.declareGlobArrayField("relatedInstr", () -> GrpInstrument.TYPE);
            TYPE = typeBuilder.build();
        }

        public static class GrpInstrument {
            public static final GlobType TYPE;

            public static final GlobArrayField<SecurityAltType> securityAltID;


            public static Glob create(Glob...sec) {
                return TYPE.instantiate()
                        .set(securityAltID, sec);
            }

            static {
                final GlobTypeBuilder typeBuilder = GlobTypeBuilderFactory.create("GrpcInstruments");
                typeBuilder.addAnnotation(FixGroupType.create("NoRelatedSym"));
                securityAltID = typeBuilder.declareGlobArrayField("securityAltID", () -> SecurityAltType.TYPE);
                TYPE = typeBuilder.build();
            }
        }

        public static class SecurityAltType {
            public static final GlobType TYPE;

            public static final StringField SecurityAltID;

            public static Glob create(String sec) {
                return TYPE.instantiate()
                        .set(SecurityAltID, sec);
            }

            static {
                final GlobTypeBuilder typeBuilder = GlobTypeBuilderFactory.create("SecurityAltType");
                typeBuilder.addAnnotation(FixGroupType.create("NoSecurityAltID"));
                SecurityAltID = typeBuilder.declareStringField("SecurityAltID",
                        FixFieldType.create("SecurityAltID"));
                TYPE = typeBuilder.build();
            }
        }

    }


}
