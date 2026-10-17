// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.serializer.jackson;

import com.multiclouddb.api.codec.DocumentCodecException;

import com.multiclouddb.api.codec.TypeRef;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.multiclouddb.api.document.*;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static com.multiclouddb.api.codec.DocumentCodecException.Phase.*;
import static com.multiclouddb.api.codec.DocumentCodecException.Reason.*;
import static org.junit.jupiter.api.Assertions.*;

class JacksonDocumentCodecTest {
    record Customer(String displayName, LocalDate birthDate) {}
    record Batch<T>(List<T> items) {}
    record Message(String value) {}
    private static final ObjectMapper TREE_MAPPER = new ObjectMapper();

    @Test
    void defaultFactoryHandlesClassAndGenericObjects() {
        JacksonDocumentCodec codec = JacksonDocumentCodec.createDefault();
        Message source = new Message("hello");
        Document document = codec.encode(source, Message.class);
        assertEquals(new StringValue("hello"), document.get("value").orElseThrow());
        assertEquals(source, codec.decode(document, Message.class));
        TypeRef<Batch<Message>> type = new TypeRef<>() {};
        Batch<Message> batch = new Batch<>(List.of(source));
        assertEquals(batch, codec.decode(codec.encode(batch, type), type));
        assertFalse(AutoCloseable.class.isAssignableFrom(JacksonDocumentCodec.class));
    }

    @Test
    void codecKeepsCollectorsAndParsersIndependentAcrossThreads() throws Exception {
        JacksonDocumentCodec codec = JacksonDocumentCodec.from(new com.fasterxml.jackson.databind.json.JsonMapper());
        var pool = Executors.newFixedThreadPool(4);
        try {
            List<Callable<Boolean>> operations = new ArrayList<>();
            for (int i = 0; i < 32; i++) {
                Message value = new Message("message-" + i);
                operations.add(() -> value.equals(codec.decode(
                        codec.encode(value, Message.class), Message.class)));
            }
            for (var result : pool.invokeAll(operations)) assertTrue(result.get());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void customerNamingDateAndGenericDeserializationSurviveMapperMutation() {
        SimpleModule module = new SimpleModule();
        AtomicInteger writes = new AtomicInteger();
        AtomicInteger reads = new AtomicInteger();
        module.addSerializer(LocalDate.class, new JsonSerializer<>() {
            @Override public void serialize(LocalDate date, JsonGenerator g, SerializerProvider provider)
                    throws IOException {
                writes.incrementAndGet();
                g.writeString(date.toString().replace("-", "/"));
            }
        });
        module.addDeserializer(LocalDate.class, new JsonDeserializer<>() {
            @Override public LocalDate deserialize(JsonParser p, DeserializationContext context) throws IOException {
                reads.incrementAndGet();
                return LocalDate.parse(p.getText().replace("/", "-"));
            }
        });
        ObjectMapper mapper = new ObjectMapper().registerModule(module)
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        JacksonDocumentCodec codec = JacksonDocumentCodec.from(mapper);
        mapper.setPropertyNamingStrategy(PropertyNamingStrategies.UPPER_CAMEL_CASE);
        TypeRef<Batch<Customer>> type = new TypeRef<>() {};
        Batch<Customer> batch = new Batch<>(List.of(new Customer("Ada", LocalDate.of(2026, 10, 6))));
        Document document = codec.encode(batch, type);
        assertEquals(Document.builder().put("items", ArrayValue.of(List.of(ObjectValue.of(Map.of(
                "display_name", new StringValue("Ada"), "birth_date", new StringValue("2026/10/06")))))).build(),
                document);
        assertEquals(batch, codec.decode(document, type));
        assertEquals(1, writes.get());
        assertEquals(1, reads.get());
    }

    @Test
    void encodingDoesNotInvokeACustomerMapDeserializer() {
        SimpleModule module = new SimpleModule();
        AtomicInteger mapReads = new AtomicInteger();
        module.addDeserializer(Map.class, new JsonDeserializer<>() {
            @Override public Map<?, ?> deserialize(JsonParser p, DeserializationContext context) {
                mapReads.incrementAndGet();
                throw new IllegalStateException("must-not-run");
            }
        });
        JacksonDocumentCodec codec = JacksonDocumentCodec.from(new ObjectMapper().registerModule(module));
        assertEquals(new StringValue("hello"), codec.encode(new Message("hello"), Message.class)
                .get("value").orElseThrow());
        assertEquals(0, mapReads.get());
    }

    @Test
    void materializesNestedGraphsAndExplicitNullWithoutRetainingMutableCollections() {
        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("nullable", null);
        List<Object> items = new ArrayList<>(Arrays.asList(inner, null, "x"));
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("items", items);
        Document output = JacksonDocumentCodec.createDefault()
                .encode(source, new TypeRef<Map<String, Object>>() {});
        List<DocumentValue> encodedItems = ((ArrayValue) output.get("items").orElseThrow()).values();
        Map<String, DocumentValue> encodedInner = ((ObjectValue) encodedItems.get(0)).fields();
        inner.put("nullable", "changed");
        items.clear();
        source.clear();
        assertTrue(encodedInner.containsKey("nullable"));
        assertEquals(NullValue.INSTANCE, encodedInner.get("nullable"));
        assertEquals(NullValue.INSTANCE, encodedItems.get(1));
        assertEquals(3, encodedItems.size());
        assertThrows(UnsupportedOperationException.class, output.root().fields()::clear);
        assertThrows(UnsupportedOperationException.class, encodedInner::clear);
        assertThrows(UnsupportedOperationException.class, encodedItems::clear);
    }

    @Test
    void retainsFiniteNumericKindsScaleAndFloatingSigns() {
        JacksonDocumentCodec codec = custom((g, value) -> {
            g.writeStartObject();
            g.writeNumberField("short", (short) 2);
            g.writeNumberField("int", 3);
            g.writeNumberField("long", 4L);
            g.writeNumberField("float", -0.0f);
            g.writeNumberField("double", 0.1d);
            g.writeNumberField("decimal", new BigDecimal("1.00"));
            g.writeNumberField("integer", new BigInteger("123456789012345678901234567890"));
            g.writeEndObject();
        });
        Map<String, Object> map = new LinkedHashMap<>();
        codec.encode(new Message("unused"), Message.class).root().fields()
                .forEach((name, value) -> map.put(name, ((NumberValue) value).value()));
        assertInstanceOf(Short.class, map.get("short"));
        assertInstanceOf(Integer.class, map.get("int"));
        assertInstanceOf(Long.class, map.get("long"));
        assertEquals(Float.floatToRawIntBits(-0.0f), Float.floatToRawIntBits((Float) map.get("float")));
        assertEquals(Double.doubleToRawLongBits(0.1d), Double.doubleToRawLongBits((Double) map.get("double")));
        assertEquals(new BigDecimal("1.00"), map.get("decimal"));
        assertEquals(new BigInteger("123456789012345678901234567890"), map.get("integer"));
    }

    @Test
    void nonfiniteAndOutOfModelNumbersFailEvenIfSerializerSwallowsTheFailure() {
        for (Number invalid : List.of(Double.NaN, Float.POSITIVE_INFINITY,
                new BigDecimal(BigInteger.ONE, 1025))) {
            assertSafeFailure(custom((g, value) -> {
                g.writeStartObject();
                g.writeFieldName("number");
                try {
                    if (invalid instanceof BigDecimal decimal) g.writeNumber(decimal);
                    else g.writeNumber(invalid.doubleValue());
                } catch (DocumentCodecException deliberatelySwallowed) {
                    // A failed collector must not be repairable by a customer serializer.
                }
                g.writeEndObject();
            }), INVALID_STRUCTURE);
        }
    }

    record IntegerTarget(int number) {}
    record FloatingTarget(float small, double large, BigDecimal decimal) {}
    record NumberKind(String kind) {}
    record ByteTarget(byte number) {}

    @Test
    void decodePreservesShortForGenericMapAndKindSensitiveCustomerReader() {
        Document document = Document.builder().put("number", NumberValue.of((short) 7)).build();
        Map<String, Object> decoded = JacksonDocumentCodec.createDefault().decode(
                document, new TypeRef<Map<String, Object>>() {});
        assertInstanceOf(Short.class, decoded.get("number"));

        SimpleModule module = new SimpleModule();
        module.addDeserializer(NumberKind.class, new JsonDeserializer<>() {
            @Override public NumberKind deserialize(JsonParser parser, DeserializationContext context)
                    throws IOException {
                parser.nextToken();
                parser.nextToken();
                String kind = parser.getNumberValue().getClass().getSimpleName();
                parser.nextToken();
                return new NumberKind(kind);
            }
        });
        JacksonDocumentCodec configured = JacksonDocumentCodec.from(new ObjectMapper().registerModule(module));
        assertEquals(new NumberKind("Short"), configured.decode(document, NumberKind.class));
    }

    @Test
    void byteModelPayloadRemainsByteButJacksonNumericTokensPromoteItToInteger() throws IOException {
        NumberValue number = NumberValue.of((byte) 7);
        Document document = Document.builder().put("number", number).build();
        assertInstanceOf(Byte.class, number.value());
        assertInstanceOf(Integer.class, JacksonDocumentCodec.createDefault().decode(
                document, new TypeRef<Map<String, Object>>() {}).get("number"));
        assertInstanceOf(Integer.class, new ObjectMapper().valueToTree((byte) 7).numberValue());
        assertEquals(new ByteTarget((byte) 7), JacksonDocumentCodec.createDefault().decode(document, ByteTarget.class),
                "typed reader conversion is separate from the numeric token representation");
        try (var tokens = new com.fasterxml.jackson.databind.util.TokenBuffer(new ObjectMapper(), false)) {
            tokens.writeNumber((short) 7);
            try (JsonParser parser = tokens.asParser()) {
                parser.nextToken();
                assertInstanceOf(Short.class, parser.getNumberValue());
            }
        }
        assertInstanceOf(Byte.class, number.value(), "decode must not rewrite the model payload");
    }

    @Test
    void sameTypeDecodeRetainsNegativeZeroAndDecimalScale() {
        JacksonDocumentCodec codec = JacksonDocumentCodec.createDefault();
        FloatingTarget source = new FloatingTarget(-0.0F, -0.0D, new BigDecimal("1.00"));
        FloatingTarget result = codec.decode(codec.encode(source, FloatingTarget.class), FloatingTarget.class);
        assertEquals(Float.floatToRawIntBits(-0.0F), Float.floatToRawIntBits(result.small()));
        assertEquals(Double.doubleToRawLongBits(-0.0D), Double.doubleToRawLongBits(result.large()));
        assertEquals(new BigDecimal("1.00"), result.decimal());
    }

    @Test
    void retainsConfiguredJacksonCoercionRatherThanImposingStrictDtoConversions() {
        Document source = Document.builder().put("number", NumberValue.of(1.5)).build();
        assertEquals(new IntegerTarget(1), JacksonDocumentCodec.createDefault().decode(source, IntegerTarget.class));
        JacksonDocumentCodec strict = JacksonDocumentCodec.from(
                new ObjectMapper().disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT));
        assertThrows(DocumentCodecException.class, () -> strict.decode(source, IntegerTarget.class));
        assertEquals(1.5, ((NumberValue) source.get("number").orElseThrow()).value());
    }

    @Test
    void rejectsObservableDuplicatesEvenWithNullValues() {
        JacksonDocumentCodec codec = custom((g, value) -> {
            g.writeStartObject();
            g.writeNullField("secret");
            g.writeStringField("secret", "customer-content");
            g.writeEndObject();
        });
        assertSafeFailure(codec, DUPLICATE_FIELD);
    }

    @Test
    void duplicateNamesInSeparateObjectsAreAllowed() {
        Map<String, Object> source = Map.of("left", Map.of("same", 1), "right", Map.of("same", 2));
        Document result = JacksonDocumentCodec.createDefault().encode(source, new TypeRef<Map<String, Object>>() {});
        assertEquals(NumberValue.of(1), ((ObjectValue) result.get("left").orElseThrow()).get("same").orElseThrow());
        assertEquals(NumberValue.of(2), ((ObjectValue) result.get("right").orElseThrow()).get("same").orElseThrow());
    }

    @Test
    void rejectsNonObjectNullMultipleAndIncompleteRoots() {
        JacksonDocumentCodec codec = JacksonDocumentCodec.createDefault();
        assertEquals(INVALID_ROOT, assertThrows(DocumentCodecException.class,
                () -> codec.encode("scalar", String.class)).reason());
        assertEquals(INVALID_ROOT, assertThrows(DocumentCodecException.class,
                () -> codec.encode(List.of(1), new TypeRef<List<Integer>>() {})).reason());
        assertEquals(INVALID_ROOT, assertThrows(DocumentCodecException.class,
                () -> codec.encode(null, Message.class)).reason());
        assertSafeFailure(custom((g, v) -> { g.writeStartObject(); }), INVALID_STRUCTURE);
        assertSafeFailure(custom((g, v) -> {
            g.writeStartObject(); g.writeEndObject(); g.writeStartObject(); g.writeEndObject();
        }), INVALID_STRUCTURE);
        assertSafeFailure(custom((g, v) -> {
            g.writeStartObject(); g.writeFieldName("secret"); g.writeEndObject();
        }), INVALID_STRUCTURE);
    }

    @Test
    void rejectsRawEmbeddedBinaryAndFormattedNumericOutputExplicitly() {
        for (Output output : List.<Output>of(
                (g, v) -> g.writeRawValue("{\"secret\":1}"),
                (g, v) -> g.writeEmbeddedObject(v),
                (g, v) -> g.writeBinary(new byte[]{1, 2}),
                (g, v) -> g.writeNumber("1.00"),
                (g, v) -> g.writeObjectId("secret"))) {
            assertSafeFailure(custom((g, value) -> {
                g.writeStartObject(); g.writeFieldName("value"); output.write(g, value); g.writeEndObject();
            }), UNSUPPORTED_OUTPUT);
        }
    }

    @Test
    void propagatesTheCallersJacksonDepthConstraintAsSafeFailure() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.getFactory().setStreamWriteConstraints(
                com.fasterxml.jackson.core.StreamWriteConstraints.builder().maxNestingDepth(1).build());
        JacksonDocumentCodec codec = JacksonDocumentCodec.from(mapper);
        assertThrows(DocumentCodecException.class, () -> codec.encode(
                Map.of("nested", Map.of("value", 1)), new TypeRef<Map<String, Object>>() {}));
    }

    @Test
    void serializerCannotSwallowDepthFailureAndReturnAPartialMap() {
        for (boolean object : List.of(true, false)) {
            SimpleModule module = new SimpleModule();
            module.addSerializer(Message.class, new JsonSerializer<>() {
                @Override public void serialize(Message value, JsonGenerator g, SerializerProvider provider)
                        throws IOException {
                    g.writeStartObject();
                    g.writeArrayFieldStart("items");
                    try {
                        if (object) g.writeStartObject();
                        else g.writeStartArray();
                    } catch (IOException deliberatelySwallowed) {
                        // Reproduce customer code attempting to recover with a partial result.
                    }
                    g.writeEndArray();
                    g.writeEndObject();
                }
            });
            ObjectMapper mapper = new ObjectMapper().registerModule(module);
            mapper.getFactory().setStreamWriteConstraints(
                    com.fasterxml.jackson.core.StreamWriteConstraints.builder().maxNestingDepth(2).build());
            assertSafeFailure(JacksonDocumentCodec.from(mapper), INVALID_STRUCTURE);
        }
    }

    @Test
    void exposesSafePhaseOnEncodeAndDecodeFailures() {
        DocumentCodecException encode = assertThrows(DocumentCodecException.class,
                () -> custom((g, v) -> { throw new IllegalArgumentException("secret"); })
                        .encode(new Message("customer-content"), Message.class));
        assertEquals(ENCODE, encode.phase());
        assertSafe(encode);
        Document bad = Document.builder().put("unexpected-secret", new StringValue("customer-content")).build();
        DocumentCodecException decode = assertThrows(DocumentCodecException.class,
                () -> JacksonDocumentCodec.createDefault().decode(bad, Message.class));
        assertEquals(DECODE, decode.phase());
        assertSafe(decode);
    }

    @Test
    void decodedTreeTargetsCannotMutateSourceDocument() {
        Document source = Document.builder().put("value", new StringValue("original")).build();
        ObjectNode decoded = JacksonDocumentCodec.createDefault().decode(source, ObjectNode.class);
        decoded.put("value", "changed");
        assertEquals(new StringValue("original"), source.get("value").orElseThrow());
    }

    record BinaryDto(byte[] bytes) {}

    @Test
    void rejectsCallerBytesBuffersAndNativeBinaryNodesAtRootAndInsideContainers() {
        JacksonDocumentCodec codec = JacksonDocumentCodec.createDefault();
        for (Object input : List.of(new byte[]{1, 2}, java.nio.ByteBuffer.wrap(new byte[]{1, 2}),
                java.nio.ByteBuffer.allocateDirect(2).put(new byte[]{1, 2}).flip(),
                com.fasterxml.jackson.databind.node.BinaryNode.valueOf(new byte[]{1, 2}))) {
            DocumentCodecException root = assertThrows(DocumentCodecException.class,
                    () -> codec.encode(input, Object.class));
            DocumentCodecException nested = assertThrows(DocumentCodecException.class,
                    () -> codec.encode(Map.of("items", List.of(Map.of("value", input))),
                            new TypeRef<Map<String, Object>>() {}));
            for (DocumentCodecException failure : List.of(root, nested)) {
                assertEquals(ENCODE, failure.phase());
                assertEquals(UNSUPPORTED_OUTPUT, failure.reason());
                assertSafe(failure);
            }
        }
        assertEquals(UNSUPPORTED_OUTPUT, assertThrows(DocumentCodecException.class,
                () -> codec.encode(new BinaryDto(new byte[]{1, 2}), BinaryDto.class)).reason());
    }

    @Test
    void explicitCustomerBase64MappingProducesOnlyAStringValue() {
        SimpleModule module = new SimpleModule();
        module.addSerializer(byte[].class, new JsonSerializer<>() {
            @Override public void serialize(byte[] bytes, JsonGenerator out, SerializerProvider context)
                    throws IOException {
                out.writeString(Base64.getEncoder().encodeToString(bytes));
            }
        });
        module.addDeserializer(byte[].class, new JsonDeserializer<>() {
            @Override public byte[] deserialize(JsonParser in, DeserializationContext context) throws IOException {
                return Base64.getDecoder().decode(in.getText());
            }
        });
        JacksonDocumentCodec codec = JacksonDocumentCodec.from(new ObjectMapper().registerModule(module));
        BinaryDto source = new BinaryDto(new byte[]{1, 2});
        Document document = codec.encode(source, BinaryDto.class);
        assertEquals(new StringValue("AQI="), document.get("bytes").orElseThrow());
        assertEquals("AQI=", codec.decode(document, new TypeRef<Map<String, Object>>() {}).get("bytes"));
        assertArrayEquals(source.bytes(), codec.decode(document, BinaryDto.class).bytes());
    }

    @Test
    void customDeserializerCanUseTheCapturedParserCodec() {
        SimpleModule module = new SimpleModule();
        module.addDeserializer(Message.class, new JsonDeserializer<>() {
            @Override public Message deserialize(JsonParser parser, DeserializationContext context)
                    throws IOException {
                ObjectNode tree = parser.readValueAsTree();
                return new Message(tree.remove("custom").textValue());
            }
        });
        JacksonDocumentCodec codec = JacksonDocumentCodec.from(new ObjectMapper().registerModule(module));
        Document source = Document.builder().put("custom", new StringValue("hello")).build();
        assertEquals(new Message("hello"), codec.decode(source, Message.class));
        assertEquals(new StringValue("hello"), source.get("custom").orElseThrow());
    }

    @Test
    void leavesExistingReservedFieldNamesUntouched() {
        Map<String, Object> source = Map.of("id", "business", "partitionKey", "business",
                "sortKey", "business", "Data", "business", "_etag", "business");
        Document output = JacksonDocumentCodec.createDefault().encode(source, new TypeRef<Map<String, Object>>() {});
        source.forEach((key, value) -> assertEquals(new StringValue((String) value), output.get(key).orElseThrow()));
    }

    @Test
    void rejectsMissingTypeAndDocumentArgumentsWithTheCorrectPhase() {
        JacksonDocumentCodec codec = JacksonDocumentCodec.createDefault();
        assertEquals(ENCODE, assertThrows(DocumentCodecException.class,
                () -> codec.encode(new Message("x"), (Class<Message>) null)).phase());
        assertEquals(DECODE, assertThrows(DocumentCodecException.class,
                () -> codec.decode(null, Message.class)).phase());
    }

    private static void assertSafeFailure(JacksonDocumentCodec codec, DocumentCodecException.Reason reason) {
        DocumentCodecException failure = assertThrows(DocumentCodecException.class,
                () -> codec.encode(new Message("customer-content"), Message.class));
        assertEquals(reason, failure.reason());
        assertEquals(ENCODE, failure.phase());
        assertSafe(failure);
    }

    private static void assertSafe(DocumentCodecException failure) {
        StringWriter text = new StringWriter();
        failure.printStackTrace(new PrintWriter(text));
        assertFalse(text.toString().contains("customer-content"));
        assertFalse(text.toString().contains("secret"));
        assertNull(failure.getCause());
        assertEquals(0, failure.getSuppressed().length);
    }

    @FunctionalInterface
    private interface Output {
        void write(JsonGenerator generator, Message value) throws IOException;
    }

    private static JacksonDocumentCodec custom(Output output) {
        SimpleModule module = new SimpleModule();
        module.addSerializer(Message.class, new JsonSerializer<>() {
            @Override public void serialize(Message value, JsonGenerator g, SerializerProvider provider)
                    throws IOException { output.write(g, value); }
        });
        return JacksonDocumentCodec.from(new ObjectMapper().registerModule(module));
    }
}
