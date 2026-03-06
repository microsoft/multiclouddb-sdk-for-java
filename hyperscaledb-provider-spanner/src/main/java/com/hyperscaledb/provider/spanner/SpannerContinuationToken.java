package com.hyperscaledb.provider.spanner;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Offset-based continuation token for Spanner query pagination.
 * <p>
 * Since Google Cloud Spanner doesn't provide a native opaque cursor (like
 * DynamoDB's {@code LastEvaluatedKey} or Cosmos DB's continuation token),
 * this implementation encodes the current row offset as a Base64 URL-safe
 * string. Spanner queries use {@code LIMIT ... OFFSET ...} to resume at
 * the right position.
 * <p>
 * Token format (before Base64): {@code offset=<number>}
 */
public final class SpannerContinuationToken {

    private static final String PREFIX = "offset=";

    private SpannerContinuationToken() {
    }

    /**
     * Encode an offset into an opaque continuation token string.
     *
     * @param offset the row offset (0-based) for the next page
     * @return a Base64-encoded token, or {@code null} if offset &lt;= 0
     */
    public static String encode(long offset) {
        if (offset <= 0) {
            return null;
        }
        String raw = PREFIX + offset;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Decode a continuation token back into a row offset.
     *
     * @param token the opaque continuation token (or {@code null})
     * @return the decoded offset, or 0 if the token is null/blank/invalid
     */
    public static long decode(String token) {
        if (token == null || token.isBlank()) {
            return 0;
        }
        try {
            String decoded = new String(
                    Base64.getUrlDecoder().decode(token),
                    StandardCharsets.UTF_8);
            if (decoded.startsWith(PREFIX)) {
                return Long.parseLong(decoded.substring(PREFIX.length()));
            }
        } catch (IllegalArgumentException e) {
            // malformed Base64 or number — treat as "start from beginning"
        }
        return 0;
    }
}
