// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.api.codec;

import com.multiclouddb.api.document.Document;

/**
 * Application-owned, thread-safe conversion between objects and immutable documents.
 * The SDK does not discover, register or invoke codecs implicitly. Implementations
 * own their conversion/coercion policy and are not closeable resources.
 */
public interface DocumentCodec {
    /** Encodes an object-root value using declared type information. */
    <T> Document encode(T value, TypeRef<T> type);

    /** Decodes a document using declared type information and the codec's policy. */
    <T> T decode(Document document, TypeRef<T> type);

    /** Convenience overload for non-generic declared classes. */
    default <T> Document encode(T value, Class<T> type) {
        return encode(value, TypeRef.of(type));
    }

    /** Convenience overload for non-generic declared classes. */
    default <T> T decode(Document document, Class<T> type) {
        return decode(document, TypeRef.of(type));
    }
}
