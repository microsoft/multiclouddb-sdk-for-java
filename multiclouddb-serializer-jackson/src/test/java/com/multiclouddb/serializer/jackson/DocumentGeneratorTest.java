// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.serializer.jackson;

import com.multiclouddb.api.codec.DocumentCodecException;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamWriteConstraints;
import com.fasterxml.jackson.core.exc.StreamConstraintsException;
import com.fasterxml.jackson.core.io.SerializedString;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiclouddb.api.document.*;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static com.multiclouddb.api.codec.DocumentCodecException.Reason.*;
import static org.junit.jupiter.api.Assertions.*;

class DocumentGeneratorTest {
    @Test
    void higherLevelGeneratorEntrypointsUseTheSameStructuralCollector() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        try (DocumentGenerator g = new DocumentGenerator(mapper)) {
            g.writeStartObject(new Object());
            g.writeFieldName(new SerializedString("text"));
            g.writeString(new SerializedString("a"));
            g.writeFieldName("chars");
            g.writeString("abcd".toCharArray(), 1, 2);
            g.writeFieldName("utf8");
            byte[] utf8 = "text".getBytes(StandardCharsets.UTF_8);
            g.writeUTF8String(utf8, 0, utf8.length);
            g.writeObjectField("object", Map.of("value", true));
            g.writeFieldName("tree");
            g.writeTree(mapper.createObjectNode().put("value", "nested"));
            g.writeFieldName("array");
            g.writeArray(new int[]{1, 2, 3}, 1, 2);
            g.writeFieldName("nullable");
            g.writeNumber((BigDecimal) null);
            g.writeEndObject();
            Map<String, DocumentValue> output = g.result().root().fields();
            assertEquals(new StringValue("a"), output.get("text"));
            assertEquals(new StringValue("bc"), output.get("chars"));
            assertEquals(new StringValue("text"), output.get("utf8"));
            assertEquals(ObjectValue.of(Map.of("value", new BooleanValue(true))), output.get("object"));
            assertEquals(ObjectValue.of(Map.of("value", new StringValue("nested"))), output.get("tree"));
            assertEquals(ArrayValue.of(List.of(NumberValue.of(2), NumberValue.of(3))), output.get("array"));
            assertTrue(output.containsKey("nullable"));
            assertEquals(NullValue.INSTANCE, output.get("nullable"));
            assertEquals(List.of("text", "chars", "utf8", "object", "tree", "array", "nullable"),
                    List.copyOf(output.keySet()));
        }
    }

    @Test
    void copyingParserStructureCannotBypassContainerMaterialization() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        var tree = mapper.createObjectNode();
        tree.putArray("items").addNull().add(2).addObject().put("value", true);
        try (DocumentGenerator generator = new DocumentGenerator(mapper);
             JsonParser parser = tree.traverse(mapper)) {
            parser.nextToken();
            generator.copyCurrentStructure(parser);
            assertEquals(Document.builder().put("items", ArrayValue.of(List.of(
                    NullValue.INSTANCE, NumberValue.of(2), ObjectValue.of(Map.of("value", new BooleanValue(true))))))
                    .build(), generator.result());
        }
    }

    @Test
    void malformedStructuresAndCaughtErrorsCannotProduceAResult() throws Exception {
        for (Output invalid : List.<Output>of(
                JsonGenerator::writeEndObject,
                JsonGenerator::writeStartArray,
                g -> { g.writeStartObject(); g.writeString("no-name"); },
                g -> { g.writeStartObject(); g.writeFieldName("first"); g.writeFieldName("second"); },
                g -> { g.writeStartObject(); g.writeFieldName("items"); g.writeStartArray(); g.writeEndObject(); },
                g -> { g.writeStartObject(); g.writeFieldName("items"); g.writeEndArray(); },
                g -> { g.writeStartObject(); g.writeEndObject(); g.writeNull(); })) {
            try (DocumentGenerator generator = new DocumentGenerator(new ObjectMapper())) {
                assertThrows(DocumentCodecException.class, () -> invalid.write(generator));
                assertThrows(DocumentCodecException.class, generator::result);
                assertThrows(DocumentCodecException.class, generator::writeStartObject);
            }
        }
    }

    @Test
    void incompleteAndClosedStreamsCannotBecomeEmptySuccesses() throws Exception {
        DocumentGenerator generator = new DocumentGenerator(new ObjectMapper());
        generator.writeStartObject();
        generator.close();
        assertThrows(DocumentCodecException.class, generator::result);
        assertThrows(DocumentCodecException.class, generator::writeStartObject);
    }

    @Test
    void objectDepthFailureRemainsFailedAfterCatchAndClose() throws Exception {
        assertDepthFailureIsSticky(JsonGenerator::writeStartObject);
    }

    @Test
    void arrayDepthFailureRemainsFailedAfterCatchAndClose() throws Exception {
        assertDepthFailureIsSticky(JsonGenerator::writeStartArray);
    }

    private static void assertDepthFailureIsSticky(Output start) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        mapper.getFactory().setStreamWriteConstraints(StreamWriteConstraints.builder().maxNestingDepth(2).build());
        try (DocumentGenerator generator = new DocumentGenerator(mapper)) {
            generator.writeStartObject();
            generator.writeFieldName("items");
            generator.writeStartArray();
            assertThrows(StreamConstraintsException.class, () -> start.write(generator));
            assertThrows(DocumentCodecException.class, generator::writeEndArray);
            assertThrows(DocumentCodecException.class, generator::writeEndObject);
            assertThrows(DocumentCodecException.class, generator::result);
            generator.close();
            assertThrows(DocumentCodecException.class, generator::result);
        }
    }

    @Test
    void unsupportedAlternativeEntrypointsFailExplicitly() throws Exception {
        for (Output output : List.<Output>of(
                g -> g.writeRawValue(new SerializedString("{}")),
                g -> g.writeNumber(new char[]{'1'}, 0, 1),
                g -> g.writeString(new StringReader("text"), 4),
                g -> g.writeRawUTF8String(new byte[]{'x'}, 0, 1),
                g -> g.writeObjectRef("id"),
                g -> g.writeTypeId("type"))) {
            try (DocumentGenerator generator = new DocumentGenerator(new ObjectMapper())) {
                generator.writeStartObject();
                generator.writeFieldName("value");
                assertEquals(UNSUPPORTED_OUTPUT, assertThrows(DocumentCodecException.class,
                        () -> output.write(generator)).reason());
            }
        }
    }

    @Test
    void rejectsInvalidUtf8InsteadOfReplacingBytes() throws Exception {
        try (DocumentGenerator generator = new DocumentGenerator(new ObjectMapper())) {
            generator.writeStartObject();
            generator.writeFieldName("text");
            assertEquals(UNSUPPORTED_OUTPUT, assertThrows(DocumentCodecException.class,
                    () -> generator.writeUTF8String(new byte[]{(byte) 0xff}, 0, 1)).reason());
        }
    }

    @Test
    @SuppressWarnings("deprecation")
    void numericStringFormattingIsNotSilentlyIgnored() throws Exception {
        ObjectMapper mapper = new ObjectMapper().enable(JsonGenerator.Feature.WRITE_NUMBERS_AS_STRINGS);
        try (DocumentGenerator generator = new DocumentGenerator(mapper)) {
            generator.writeStartObject();
            generator.writeFieldName("nullable");
            generator.writeNumber((BigDecimal) null);
            generator.writeFieldName("number");
            assertEquals(UNSUPPORTED_OUTPUT, assertThrows(DocumentCodecException.class,
                    () -> generator.writeNumber(1)).reason());
        }
    }

    @FunctionalInterface
    private interface Output {
        void write(JsonGenerator generator) throws IOException;
    }
}
