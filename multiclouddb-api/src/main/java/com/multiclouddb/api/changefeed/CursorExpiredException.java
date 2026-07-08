// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.api.changefeed;

import com.multiclouddb.api.MulticloudDbError;
import com.multiclouddb.api.MulticloudDbErrorCategory;
import com.multiclouddb.api.MulticloudDbException;

/**
 * Thrown by {@link com.multiclouddb.api.changefeed.ChangeFeedCursor#fromToken(String)}
 * and {@link com.multiclouddb.api.MulticloudDbClient#readChanges(
 * com.multiclouddb.api.ResourceAddress, ChangeFeedCursor)} when a cursor cannot
 * be safely resumed.
 *
 * <h3>When this is thrown</h3>
 * <ul>
 *   <li><b>Client-side age-out</b> — the token's last-issued timestamp is older
 *       than the v1 portable 24-hour baseline. The {@code reason} detail is
 *       {@code TOKEN_AGED_OUT}.</li>
 *   <li><b>Provider-side trim</b> — the provider has dropped the events the
 *       cursor pointed at (Cosmos {@code 410 GONE} /
 *       {@code TrimmedDataAccessException} / Spanner
 *       {@code INVALID_ARGUMENT}). The {@code reason} detail is
 *       {@code PROVIDER_TRIMMED}.</li>
 *   <li><b>Iterator expired</b> — a persisted server-side iterator handle has
 *       aged out (DynamoDB Streams' ~5-minute inactivity window on a
 *       persisted shard iterator) before the cursor observed its next page.
 *       The {@code reason} detail is {@code ITERATOR_EXPIRED}; recovery is to
 *       re-bootstrap with {@code listCursors()} from the live tip.</li>
 *   <li><b>Token mismatch</b> — the token was minted by a different provider
 *       or for a different resource ({@code PROVIDER_MISMATCH} /
 *       {@code RESOURCE_MISMATCH}).</li>
 *   <li><b>Malformed token</b> — the token cannot be parsed or its version is
 *       newer than this SDK supports ({@code MALFORMED} /
 *       {@code VERSION_UNSUPPORTED}).</li>
 * </ul>
 *
 * <h3>Recovery</h3>
 * Per the v1 contract, the only safe recovery is to restart the change-feed
 * worker from a fresh starting point — usually
 * {@link ChangeFeedCursor#now()} for a "lossy resume", or a re-call of
 * {@link com.multiclouddb.api.MulticloudDbClient#listCursors(
 * com.multiclouddb.api.ResourceAddress)} to rediscover live partitions.
 * Downstream pipelines must be idempotent at the primary-key level to absorb
 * any gap.
 *
 * <p>The {@code reason} detail is exposed via
 * {@link MulticloudDbError#providerDetails() error().providerDetails().get("reason")}
 * and is one of the public string constants on
 * {@link com.multiclouddb.api.changefeed.internal.CursorTokenCodec}
 * ({@code MALFORMED}, {@code VERSION_UNSUPPORTED}, {@code TOKEN_AGED_OUT},
 * {@code PROVIDER_MISMATCH}, {@code RESOURCE_MISMATCH},
 * {@code PROVIDER_TRIMMED}, {@code ITERATOR_EXPIRED}) — providers may also
 * surface additional diagnostic details under provider-specific keys.
 *
 * <p>Always carries
 * {@link MulticloudDbErrorCategory#CURSOR_EXPIRED} as the error category.
 */
public class CursorExpiredException extends MulticloudDbException {

    public CursorExpiredException(MulticloudDbError error) {
        super(requireCategory(error));
    }

    public CursorExpiredException(MulticloudDbError error, Throwable cause) {
        super(requireCategory(error), cause);
    }

    private static MulticloudDbError requireCategory(MulticloudDbError error) {
        if (error == null || !MulticloudDbErrorCategory.CURSOR_EXPIRED.equals(error.category())) {
            throw new IllegalArgumentException(
                    "CursorExpiredException must carry MulticloudDbErrorCategory.CURSOR_EXPIRED");
        }
        return error;
    }
}
