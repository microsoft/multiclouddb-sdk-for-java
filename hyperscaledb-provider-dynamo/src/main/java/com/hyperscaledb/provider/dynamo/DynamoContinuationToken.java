// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.hyperscaledb.provider.dynamo;

import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.util.*;

/**
 * Serializes/deserializes DynamoDB's {@code LastEvaluatedKey} as an opaque
 * continuation token string, using a simple pipe-delimited format.
 * <p>
 * Format: key1=S:value1|key2=N:value2|...
 */
public final class DynamoContinuationToken {

    private DynamoContinuationToken() {
    }

    /**
     * Encode a DynamoDB LastEvaluatedKey map into an opaque string token.
     */
    public static String encode(Map<String, AttributeValue> lastEvaluatedKey) {
        if (lastEvaluatedKey == null || lastEvaluatedKey.isEmpty()) {
            return null;
        }

        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, AttributeValue> entry : lastEvaluatedKey.entrySet()) {
            if (!first)
                sb.append('|');
            first = false;
            sb.append(entry.getKey()).append('=');
            AttributeValue av = entry.getValue();
            if (av.s() != null && av.type() == AttributeValue.Type.S) {
                sb.append("S:").append(base64Encode(av.s()));
            } else if (av.n() != null && av.type() == AttributeValue.Type.N) {
                sb.append("N:").append(av.n());
            } else {
                // Fallback: treat as string
                sb.append("S:").append(base64Encode(av.toString()));
            }
        }
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * Decode an opaque continuation token back into a DynamoDB ExclusiveStartKey
     * map.
     */
    public static Map<String, AttributeValue> decode(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }

        String decoded = new String(
                Base64.getUrlDecoder().decode(token),
                java.nio.charset.StandardCharsets.UTF_8);

        Map<String, AttributeValue> key = new LinkedHashMap<>();
        for (String part : decoded.split("\\|")) {
            int eqIdx = part.indexOf('=');
            if (eqIdx < 0)
                continue;
            String attrName = part.substring(0, eqIdx);
            String typeAndValue = part.substring(eqIdx + 1);
            if (typeAndValue.startsWith("S:")) {
                String val = base64Decode(typeAndValue.substring(2));
                key.put(attrName, AttributeValue.fromS(val));
            } else if (typeAndValue.startsWith("N:")) {
                key.put(attrName, AttributeValue.fromN(typeAndValue.substring(2)));
            }
        }
        return key;
    }

    private static String base64Encode(String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static String base64Decode(String value) {
        return new String(
                Base64.getUrlDecoder().decode(value),
                java.nio.charset.StandardCharsets.UTF_8);
    }
}
