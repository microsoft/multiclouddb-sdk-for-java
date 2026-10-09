// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.serializer.jackson;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.util.TokenBuffer;
import com.multiclouddb.api.codec.DocumentCodecException;
import com.multiclouddb.api.codec.TypeRef;
import com.multiclouddb.api.document.Document;
import java.io.IOException;

import static com.multiclouddb.api.codec.DocumentCodecException.Phase.CONSTRUCTION;
import static com.multiclouddb.api.codec.DocumentCodecException.Reason.INVALID_ARGUMENT;
import static com.multiclouddb.api.codec.DocumentCodecException.Reason.MAPPER_COPY_FAILED;

/**
 * Captures mapper configuration without owning customer collaborator lifecycles.
 * Copy is not a deep clone of modules/serializers; those must be thread-safe and
 * must not be mutated after construction. No mapper is exposed to application code.
 */
final class MapperSnapshot {
    private final ObjectMapper mapper;

    private MapperSnapshot(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    static MapperSnapshot from(ObjectMapper source) {
        if (source == null) {
            throw new DocumentCodecException(CONSTRUCTION, INVALID_ARGUMENT);
        }
        final ObjectMapper copy;
        try {
            // This is the caller-overridable copy boundary, not a general SDK catch.
            copy = source.copy();
        } catch (RuntimeException failure) {
            throw new DocumentCodecException(CONSTRUCTION, MAPPER_COPY_FAILED);
        }
        if (copy == null || copy == source || !source.getClass().isInstance(copy)
                || copy.getFactory() == source.getFactory()) {
            throw new DocumentCodecException(CONSTRUCTION, MAPPER_COPY_FAILED);
        }
        return new MapperSnapshot(copy);
    }

    ObjectWriter writerFor(TypeRef<?> type) {
        return mapper.writerFor(mapper.constructType(type.type()));
    }

    ObjectReader readerFor(TypeRef<?> type) {
        return mapper.readerFor(mapper.constructType(type.type()));
    }

    DocumentGenerator generator() {
        return new DocumentGenerator(mapper);
    }

    TokenBuffer tokensFor(Document document) throws IOException {
        TokenBuffer tokens = new TokenBuffer(mapper, false);
        try {
            DocumentTokens.write(tokens, document.root());
            return tokens;
        } catch (IOException | RuntimeException failure) {
            tokens.close();
            throw failure;
        }
    }
}
