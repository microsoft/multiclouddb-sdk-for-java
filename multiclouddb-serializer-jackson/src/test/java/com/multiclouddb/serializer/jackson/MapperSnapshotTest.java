// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.serializer.jackson;

import com.multiclouddb.api.codec.DocumentCodecException;

import com.multiclouddb.api.codec.TypeRef;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import com.fasterxml.jackson.databind.util.TokenBuffer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static com.multiclouddb.api.codec.DocumentCodecException.Phase.CONSTRUCTION;
import static com.multiclouddb.api.codec.DocumentCodecException.Reason.*;
import static org.junit.jupiter.api.Assertions.*;

class MapperSnapshotTest {
    record Customer(String displayName) {}
    record Batch<T>(List<T> items) {}

    @Test
    void capturesNamingAndGenericMappingWithoutExposingItsMapper() throws Exception {
        ObjectMapper source = new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        MapperSnapshot snapshot = MapperSnapshot.from(source);
        source.setPropertyNamingStrategy(PropertyNamingStrategies.UPPER_CAMEL_CASE);
        TypeRef<Batch<Customer>> type = new TypeRef<>() {};
        Batch<Customer> value = new Batch<>(List.of(new Customer("example")));
        try (TokenBuffer buffer = new TokenBuffer(source, false)) {
            snapshot.writerFor(type).writeValue(buffer, value);
            List<String> names = new ArrayList<>();
            try (JsonParser parser = buffer.asParser()) {
                while (parser.nextToken() != null) {
                    if (parser.currentToken() == com.fasterxml.jackson.core.JsonToken.FIELD_NAME) {
                        names.add(parser.currentName());
                    }
                }
            }
            assertEquals(List.of("items", "display_name"), names);
            try (JsonParser parser = buffer.asParser()) {
                assertEquals(value, snapshot.readerFor(type).readValue(parser));
            }
        }
    }

    @Test
    void preservesRegisteredSerializerWithoutFallbackOrLaterMapperChanges() throws Exception {
        SimpleModule module = new SimpleModule();
        module.addSerializer(Customer.class, new StdSerializer<Customer>(Customer.class) {
            @Override
            public void serialize(Customer value, JsonGenerator generator, SerializerProvider provider)
                    throws IOException {
                generator.writeStartObject();
                generator.writeStringField("configured", value.displayName());
                generator.writeEndObject();
            }
        });
        module.addDeserializer(Customer.class, new JsonDeserializer<Customer>() {
            @Override
            public Customer deserialize(JsonParser parser, DeserializationContext context) throws IOException {
                JsonNode configuredInput = parser.readValueAsTree();
                return new Customer(configuredInput.get("configured").textValue());
            }
        });
        ObjectMapper source = new ObjectMapper().registerModule(module);
        MapperSnapshot snapshot = MapperSnapshot.from(source);
        source.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        try (TokenBuffer buffer = new TokenBuffer(source, false)) {
            snapshot.writerFor(TypeRef.of(Customer.class)).writeValue(buffer, new Customer("example"));
            try (JsonParser parser = buffer.asParser()) {
                assertEquals(com.fasterxml.jackson.core.JsonToken.START_OBJECT, parser.nextToken());
                assertEquals(com.fasterxml.jackson.core.JsonToken.FIELD_NAME, parser.nextToken());
                assertEquals("configured", parser.currentName());
            }
            try (JsonParser parser = buffer.asParser()) {
                assertEquals(new Customer("example"), snapshot.readerFor(TypeRef.of(Customer.class)).readValue(parser));
            }
        }
    }

    @Test
    void supportsStockJsonMapperAndConcurrentIndependentReadersWriters() throws Exception {
        MapperSnapshot snapshot = MapperSnapshot.from(new JsonMapper());
        var pool = Executors.newFixedThreadPool(4);
        try {
            List<Callable<Boolean>> operations = new ArrayList<>();
            for (int i = 0; i < 32; i++) {
                String name = "customer-" + i;
                operations.add(() -> {
                    Customer value = new Customer(name);
                    try (TokenBuffer buffer = new TokenBuffer(null, false)) {
                        snapshot.writerFor(TypeRef.of(Customer.class)).writeValue(buffer, value);
                        try (JsonParser parser = buffer.asParser()) {
                            return value.equals(snapshot.readerFor(TypeRef.of(Customer.class)).readValue(parser));
                        }
                    }
                });
            }
            for (var result : pool.invokeAll(operations)) assertTrue(result.get());
        } finally {
            pool.shutdownNow();
        }
        assertFalse(AutoCloseable.class.isAssignableFrom(MapperSnapshot.class));
    }

    @Test
    void copyFailureCannotLeakOriginalMessageCauseOrSuppressedDetails() {
        ObjectMapper source = new ObjectMapper() {
            @Override
            public ObjectMapper copy() {
                throw new IllegalStateException("customer-secret");
            }
        };
        DocumentCodecException failure = assertThrows(DocumentCodecException.class,
                () -> JacksonDocumentCodec.from(source));
        assertEquals(CONSTRUCTION, failure.phase());
        assertEquals(MAPPER_COPY_FAILED, failure.reason());
        assertNull(failure.getCause());
        assertEquals(0, failure.getSuppressed().length);
        StringWriter trace = new StringWriter();
        failure.printStackTrace(new PrintWriter(trace));
        assertFalse(trace.toString().contains("customer-secret"));
    }

    @Test
    void rejectsNullSelfNullCopyAndIncompatibleCopy() {
        assertEquals(INVALID_ARGUMENT, assertThrows(DocumentCodecException.class,
                () -> JacksonDocumentCodec.from(null)).reason());
        for (ObjectMapper source : List.of(
                new ObjectMapper() { @Override public ObjectMapper copy() { return this; } },
                new ObjectMapper() { @Override public ObjectMapper copy() { return null; } },
                new ObjectMapper() { @Override public ObjectMapper copy() { return new ObjectMapper(); } },
                new ObjectMapper() {})) {
            assertEquals(MAPPER_COPY_FAILED, assertThrows(DocumentCodecException.class,
                    () -> JacksonDocumentCodec.from(source)).reason());
        }
    }

    @Test
    void supportsSubclassWithIndependentCompatibleCopy() {
        assertNotNull(JacksonDocumentCodec.from(new CopyableMapper()));
    }

    static final class CopyableMapper extends ObjectMapper {
        CopyableMapper() {}
        private CopyableMapper(CopyableMapper source) { super(source); }
        @Override public CopyableMapper copy() { return new CopyableMapper(this); }
    }
}
