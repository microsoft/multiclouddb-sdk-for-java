// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.api.changefeed;

/**
 * Where to begin reading the change feed.
 * <p>
 * Sealed interface with three fully-portable variants — every provider
 * (Cosmos, DynamoDB, Spanner) supports the same surface:
 * <ul>
 *   <li>{@link Beginning} — start at the earliest available change in the
 *       feed. Subject to provider retention windows (Dynamo: 24h, Spanner:
 *       configurable, Cosmos: indefinite).</li>
 *   <li>{@link Now} — start with changes committed after the call.</li>
 *   <li>{@link FromContinuationToken} — resume from a previously-issued
 *       opaque token. The token is provider+resource scoped; cross-provider
 *       use fails with {@link com.multiclouddb.api.MulticloudDbErrorCategory#INVALID_REQUEST}.
 *       A trimmed cursor (e.g. Dynamo retention exceeded) fails with
 *       {@link com.multiclouddb.api.MulticloudDbErrorCategory#CHECKPOINT_EXPIRED}.</li>
 * </ul>
 */
public sealed interface StartPosition
        permits StartPosition.Beginning,
                StartPosition.Now,
                StartPosition.FromContinuationToken {

    /** Start from the earliest available change. */
    static StartPosition beginning() {
        return Beginning.INSTANCE;
    }

    /** Start from changes committed after the call. */
    static StartPosition now() {
        return Now.INSTANCE;
    }

    /**
     * Resume from a previously-issued continuation token.
     * @throws IllegalArgumentException if {@code token} is null/blank
     */
    static StartPosition fromContinuationToken(String token) {
        return new FromContinuationToken(token);
    }

    final class Beginning implements StartPosition {
        static final Beginning INSTANCE = new Beginning();

        private Beginning() {
        }

        @Override
        public String toString() {
            return "StartPosition.Beginning";
        }
    }

    final class Now implements StartPosition {
        static final Now INSTANCE = new Now();

        private Now() {
        }

        @Override
        public String toString() {
            return "StartPosition.Now";
        }
    }

    record FromContinuationToken(String token) implements StartPosition {
        public FromContinuationToken {
            if (token == null || token.isBlank()) {
                throw new IllegalArgumentException("token must be non-blank");
            }
        }
    }
}
