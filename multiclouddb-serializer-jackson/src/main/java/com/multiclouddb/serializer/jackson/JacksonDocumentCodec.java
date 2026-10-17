// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.serializer.jackson;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.util.TokenBuffer;
import com.multiclouddb.api.codec.DocumentCodec;
import com.multiclouddb.api.codec.DocumentCodecException;
import com.multiclouddb.api.codec.TypeRef;
import com.multiclouddb.api.document.Document;

import java.io.IOException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import static com.multiclouddb.api.codec.DocumentCodecException.Phase.*;
import static com.multiclouddb.api.codec.DocumentCodecException.Reason.*;

/**
 * Optional application-owned Jackson mapping for immutable documents.
 * Invoke encode/decode explicitly; clients never discover or register codecs.
 * Provider schema, metadata and numeric mapping limitations still apply.
 *
 * <p>Mapper configuration is copied, not shared. Custom collaborators are not
 * necessarily deep-cloned and must remain thread-safe and unmodified. Instances
 * have no closeable lifecycle. Configured reader coercion is retained; mathematical
 * document equality does not promise lossless conversion to every DTO target.
 */
public final class JacksonDocumentCodec implements DocumentCodec {
    private final MapperSnapshot snapshot;

    private JacksonDocumentCodec(MapperSnapshot snapshot) {
        this.snapshot = snapshot;
    }

    /** Stock mapping without module discovery or enabling default typing. */
    public static JacksonDocumentCodec createDefault() {
        return from(new ObjectMapper());
    }

    /** Snapshots a configured mapper, failing explicitly if it cannot be copied. */
    public static JacksonDocumentCodec from(ObjectMapper mapper) {
        return new JacksonDocumentCodec(MapperSnapshot.from(mapper));
    }

    /**
     * Collects object-root tokens directly into immutable values without JSON
     * bytes, Map-target deserialization or reserved-field rewriting.
     */
    @Override
    public <T> Document encode(T value, TypeRef<T> type) {
        if (type == null) throw new DocumentCodecException(ENCODE, INVALID_ARGUMENT);
        if (value == null) throw new DocumentCodecException(ENCODE, INVALID_ROOT);
        try (DocumentGenerator generator = snapshot.generator()) {
            snapshot.writerFor(type).writeValue(generator, value);
            return generator.result();
        } catch (IOException | RuntimeException failure) {
            throw safeFailure(ENCODE, failure);
        }
    }

    @Override
    public <T> Document encode(T value, Class<T> type) {
        if (type == null) throw new DocumentCodecException(ENCODE, INVALID_ARGUMENT);
        return encode(value, TypeRef.of(type));
    }

    /**
     * Uses the captured typed reader on decoder-owned tokens. Short and the other
     * supported numeric kinds are retained; Byte is promoted to Integer because
     * Jackson has no byte numeric token. The immutable model is not rewritten.
     * Customer deserializers and coercion settings are not overridden.
     * No provider metadata is stripped here.
     */
    @Override
    public <T> T decode(Document document, TypeRef<T> type) {
        if (document == null || type == null) throw new DocumentCodecException(DECODE, INVALID_ARGUMENT);
        try (TokenBuffer tokens = snapshot.tokensFor(document);
             JsonParser parser = tokens.asParser()) {
            return snapshot.readerFor(type).readValue(parser);
        } catch (IOException | RuntimeException failure) {
            throw safeFailure(DECODE, failure);
        }
    }

    @Override
    public <T> T decode(Document document, Class<T> type) {
        if (type == null) throw new DocumentCodecException(DECODE, INVALID_ARGUMENT);
        return decode(document, TypeRef.of(type));
    }

    private static DocumentCodecException safeFailure(DocumentCodecException.Phase phase, Exception failure) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable cause = failure; cause != null && visited.add(cause); cause = cause.getCause()) {
            if (cause instanceof DocumentCodecException known && known.phase() == phase) return known;
        }
        return new DocumentCodecException(phase, MAPPING_FAILED);
    }
}
