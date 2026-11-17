// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.
package com.multiclouddb.api;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.function.BiFunction;
import java.util.function.Function;

/** Test-owned adapter for the released Jackson JSON profile, not an SDK default. */
public final class TestJson {
    private static final ObjectMapper MAPPER = new ObjectMapper(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(1000)
                    .maxNumberLength(1000).maxStringLength(20_000_000).maxNameLength(50_000).build())
            .build())
            .disable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .disable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .disable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
            .disable(DeserializationFeature.USE_LONG_FOR_INTS);
    public static final Function<Object, String> SERIALIZE = TestJson::write;
    public static final BiFunction<String, Class<?>, Object> DESERIALIZE = TestJson::read;

    private TestJson() {}

    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    public static Object read(String json, Class<?> target) {
        if (target != Object.class) {
            throw new IllegalArgumentException("Unsupported JSON target.");
        }
        try (var parser = MAPPER.createParser(json)) {
            if (parser.nextToken() == null) return null;
            return MAPPER.readValue(parser, target);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
