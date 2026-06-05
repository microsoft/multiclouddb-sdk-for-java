// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.api.changefeed.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.multiclouddb.api.MulticloudDbError;
import com.multiclouddb.api.MulticloudDbErrorCategory;
import com.multiclouddb.api.MulticloudDbException;
import com.multiclouddb.api.ProviderId;
import com.multiclouddb.api.ResourceAddress;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

/**
 * Encode/decode the SDK's portable change-feed continuation-token envelope.
 * <p>
 * Format: base64(JSON) where JSON has shape:
 * <pre>{@code
 *   {
 *     "v": <int schema version, currently 1>,
 *     "p": "<provider id>",
 *     "r": "<resource fingerprint = database/collection>",
 *     "c": <provider cursor — string or object, opaque to this codec>
 *   }
 * }</pre>
 *
 * <p>Provider clients call {@link #encode(ProviderId, ResourceAddress, JsonNode)}
 * to wrap their native cursor and {@link #decode(String, ProviderId, ResourceAddress)}
 * to validate and unwrap on resume. Mismatches in version, provider id, or
 * resource fingerprint surface as {@link MulticloudDbErrorCategory#INVALID_REQUEST}.
 *
 * <p><b>Security note.</b> The envelope provides <em>structural validation</em>
 * only (schema version, provider id, and {@code database/collection}
 * resource fingerprint). It is <b>not</b> cryptographically protected:
 * tokens are not signed or MAC’d, so a caller in possession of a token
 * can decode and modify the inner cursor for the same provider+resource.
 * Treat continuation tokens as caller-trusted state. Cryptographic
 * authenticity (HMAC-keyed envelope) is tracked as a follow-up and will
 * bump {@link #CURRENT_VERSION} when shipped.
 */
public final class ContinuationTokenCodec {

    public static final int CURRENT_VERSION = 1;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Base64.Encoder B64E = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();

    private ContinuationTokenCodec() {
    }

    /**
     * Wrap the given provider cursor in a versioned envelope.
     *
     * @param provider       provider id (e.g. {@link ProviderId#COSMOS})
     * @param address        the resource the cursor refers to
     * @param providerCursor opaque provider-native cursor; can be a string,
     *                       array, or object
     * @return base64url-encoded envelope; non-null
     */
    public static String encode(ProviderId provider, ResourceAddress address, JsonNode providerCursor) {
        ObjectNode envelope = MAPPER.createObjectNode();
        envelope.put("v", CURRENT_VERSION);
        envelope.put("p", provider.id());
        envelope.put("r", fingerprint(address));
        envelope.set("c", providerCursor != null ? providerCursor : MAPPER.nullNode());
        try {
            byte[] bytes = MAPPER.writeValueAsBytes(envelope);
            return B64E.encodeToString(bytes);
        } catch (Exception e) {
            throw new MulticloudDbException(new MulticloudDbError(
                    MulticloudDbErrorCategory.PROVIDER_ERROR,
                    "Failed to serialize continuation token: " + e.getMessage(),
                    provider, "readChanges", false,
                    Map.of("exceptionType", e.getClass().getName())), e);
        }
    }

    /**
     * Decode and validate the envelope. Throws {@link MulticloudDbException}
     * with category {@link MulticloudDbErrorCategory#INVALID_REQUEST} when the
     * token is malformed, was issued for a different provider, or addresses a
     * different resource than the current request.
     *
     * @return the inner provider cursor JSON node
     */
    public static JsonNode decode(String token, ProviderId provider, ResourceAddress address) {
        if (token == null || token.isBlank()) {
            throw invalid(provider, "continuation token must be non-blank", null);
        }
        ObjectNode envelope;
        try {
            byte[] bytes = B64D.decode(token);
            JsonNode root = MAPPER.readTree(bytes);
            if (!(root instanceof ObjectNode obj)) {
                throw invalid(provider, "continuation token is not a JSON object", null);
            }
            envelope = obj;
        } catch (MulticloudDbException e) {
            throw e;
        } catch (Exception e) {
            throw invalid(provider, "malformed continuation token: " + e.getMessage(), e);
        }

        int v = envelope.path("v").asInt(-1);
        if (v != CURRENT_VERSION) {
            throw invalid(provider,
                    "continuation token schema version " + v + " is not supported (expected "
                            + CURRENT_VERSION + ")",
                    null);
        }
        String p = envelope.path("p").asText("");
        if (!provider.id().equals(p)) {
            throw invalid(provider,
                    "continuation token was issued for provider '" + p
                            + "' but the current client provider is '" + provider.id() + "'",
                    null);
        }
        String r = envelope.path("r").asText("");
        String expected = fingerprint(address);
        if (!expected.equals(r)) {
            throw invalid(provider,
                    "continuation token was issued for resource '" + r
                            + "' but the current request targets '" + expected + "'",
                    null);
        }
        return envelope.path("c");
    }

    private static String fingerprint(ResourceAddress address) {
        return address.database() + "/" + address.collection();
    }

    private static MulticloudDbException invalid(ProviderId provider, String message, Throwable cause) {
        MulticloudDbError err = new MulticloudDbError(
                MulticloudDbErrorCategory.INVALID_REQUEST,
                message, provider, "readChanges", false, Map.of());
        return cause != null ? new MulticloudDbException(err, cause)
                : new MulticloudDbException(err);
    }
}
