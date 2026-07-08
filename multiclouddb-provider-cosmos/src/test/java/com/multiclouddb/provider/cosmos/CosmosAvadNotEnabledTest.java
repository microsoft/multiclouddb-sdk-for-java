// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.provider.cosmos;

import com.azure.cosmos.CosmosContainer;
import com.azure.cosmos.CosmosException;
import com.azure.cosmos.models.CosmosChangeFeedRequestOptions;
import com.azure.cosmos.models.FeedRange;
import com.fasterxml.jackson.databind.JsonNode;
import com.multiclouddb.api.MulticloudDbErrorCategory;
import com.multiclouddb.api.MulticloudDbException;
import com.multiclouddb.api.ProviderId;
import com.multiclouddb.api.ResourceAddress;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CosmosChangeFeedReader#maybeAvadNotEnabled(CosmosException, String)}
 * — the Cosmos 400-BadRequest fingerprint that re-maps "container is not
 * provisioned for All-Versions-and-Deletes" to the portable
 * {@link MulticloudDbErrorCategory#UNSUPPORTED_CAPABILITY} category so callers
 * get the same actionable signal the Dynamo and Spanner readers surface for the
 * same operational mistake (stream / change-stream not enabled).
 * <p>
 * Without this re-mapping a non-AVAD container would surface
 * {@code INVALID_REQUEST} via the generic {@code CosmosErrorMapper},
 * forcing portable consumers to substring-match on Cosmos to disambiguate
 * provisioning failures from genuine malformed-input errors. The four
 * fingerprint substrings cover the known Cosmos message wordings across
 * service versions; the helper returns {@code null} on a no-match so the
 * caller falls through to the generic mapper.
 * <p>
 * Tests reach the private helper via reflection (kept package-private would
 * leak it onto the public Cosmos reader surface). Reflection is acceptable
 * here because the helper is a pure, side-effect-free string-matcher whose
 * contract is part of the cross-provider parity invariant.
 */
class CosmosAvadNotEnabledTest {

    private static final ResourceAddress ADDR = new ResourceAddress("test-db", "test-col");

    private static MulticloudDbException invoke(int statusCode, String message, String op) throws Exception {
        CosmosException ex = mock(CosmosException.class);
        when(ex.getStatusCode()).thenReturn(statusCode);
        when(ex.getMessage()).thenReturn(message);
        CosmosChangeFeedReader reader = new CosmosChangeFeedReader(ProviderId.COSMOS);
        Method m = CosmosChangeFeedReader.class.getDeclaredMethod(
                "maybeAvadNotEnabled", CosmosException.class, String.class);
        m.setAccessible(true);
        return (MulticloudDbException) m.invoke(reader, ex, op);
    }

    private static void assertAvadFingerprint(MulticloudDbException result, String op, int expectedStatus) {
        assertNotNull(result, "AVAD-not-enabled fingerprint must produce a non-null normalised exception");
        assertEquals(MulticloudDbErrorCategory.UNSUPPORTED_CAPABILITY, result.error().category(),
                "AVAD-not-enabled MUST map to the portable UNSUPPORTED_CAPABILITY category "
                        + "(symmetric with Dynamo / Spanner stream_not_enabled)");
        assertEquals("avad_not_enabled", result.error().providerDetails().get("reason"),
                "providerDetails.reason MUST be the wire-stable string 'avad_not_enabled'");
        assertEquals(String.valueOf(expectedStatus), result.error().providerDetails().get("statusCode"));
        assertEquals(op, result.error().operation());
        assertEquals(ProviderId.COSMOS, result.error().provider());
        assertFalse(result.error().retryable(),
                "AVAD-not-enabled is a provisioning misconfiguration — never retryable");
    }

    // ── Fingerprint variants ────────────────────────────────────────────────

    @Test
    @DisplayName("400 + lowercase 'allversionsanddeletes' → UNSUPPORTED_CAPABILITY(reason=avad_not_enabled)")
    void fingerprint_allVersionsAndDeletes_oneWord() throws Exception {
        MulticloudDbException r = invoke(400,
                "Container does not have allversionsanddeletes ChangeFeedPolicy enabled", "listCursors");
        assertAvadFingerprint(r, "listCursors", 400);
    }

    @Test
    @DisplayName("400 + spaced 'all versions and deletes' → fingerprint match")
    void fingerprint_allVersionsAndDeletes_spaced() throws Exception {
        MulticloudDbException r = invoke(400,
                "BadRequest: All Versions And Deletes mode is required for this operation", "readChanges");
        assertAvadFingerprint(r, "readChanges", 400);
    }

    @Test
    @DisplayName("400 + 'change feed mode' wording → fingerprint match")
    void fingerprint_changeFeedMode() throws Exception {
        MulticloudDbException r = invoke(400,
                "The requested change feed mode is not enabled on this container", "readChanges");
        assertAvadFingerprint(r, "readChanges", 400);
    }

    @Test
    @DisplayName("400 + 'ChangeFeedPolicy' class-name wording → fingerprint match")
    void fingerprint_changeFeedPolicyClassName() throws Exception {
        MulticloudDbException r = invoke(400,
                "ChangeFeedPolicy must be configured on the container", "listCursors");
        assertAvadFingerprint(r, "listCursors", 400);
    }

    @Test
    @DisplayName("Case-insensitive matching: 'AllVersionsAndDeletes' (mixed case) matches")
    void fingerprint_mixedCase() throws Exception {
        MulticloudDbException r = invoke(400,
                "Use AllVersionsAndDeletes to receive deletion records", "listCursors");
        assertAvadFingerprint(r, "listCursors", 400);
    }

    // ── No-match negative paths ─────────────────────────────────────────────

    @Test
    @DisplayName("400 + unrelated message → returns null (fall through to generic mapper)")
    void noMatch_unrelated400_returnsNull() throws Exception {
        // A 400 BadRequest with an unrelated message (e.g. document size, bad
        // partition key) must NOT be re-mapped — it is a genuine
        // INVALID_REQUEST and should flow through CosmosErrorMapper unchanged.
        MulticloudDbException r = invoke(400,
                "Document size exceeds the maximum allowed", "readChanges");
        assertNull(r, "non-AVAD 400 must return null so caller falls through to "
                + "CosmosErrorMapper (preserves INVALID_REQUEST semantics)");
    }

    @Test
    @DisplayName("500 + AVAD message → returns null (status code gate filters non-400s)")
    void noMatch_500WithAvadMessage_returnsNull() throws Exception {
        // The helper deliberately gates on status==400 — a 500 InternalError
        // with an AVAD substring is a server bug, not a provisioning error.
        MulticloudDbException r = invoke(500,
                "Internal error processing allversionsanddeletes flag", "readChanges");
        assertNull(r, "non-400 status must return null even if the AVAD substring "
                + "appears in the message body (status-code gate is mandatory)");
    }

    @Test
    @DisplayName("400 + null message → returns null (defensive null-guard)")
    void noMatch_nullMessage_returnsNull() throws Exception {
        MulticloudDbException r = invoke(400, null, "readChanges");
        assertNull(r, "null message must return null — defensive guard, never NPE");
    }

    @Test
    @DisplayName("404 (NotFound) + AVAD message → returns null (only 400 is the AVAD gate)")
    void noMatch_404Status_returnsNull() throws Exception {
        MulticloudDbException r = invoke(404,
                "container with changefeedpolicy not found", "listCursors");
        assertNull(r);
    }

    // ── Integration: listCursors propagates the fingerprint ─────────────────

    @Test
    @DisplayName("listCursors: CosmosException(400, AVAD msg) → MulticloudDbException(UNSUPPORTED_CAPABILITY)")
    void listCursors_integration_avadNotEnabledPropagates() {
        CosmosContainer container = mock(CosmosContainer.class);
        // The reader calls container.getFeedRanges() first inside its try{};
        // throw the AVAD 400 from that call so the catch block reaches
        // maybeAvadNotEnabled before the generic mapper.
        CosmosException avadEx = mock(CosmosException.class);
        when(avadEx.getStatusCode()).thenReturn(400);
        when(avadEx.getMessage()).thenReturn(
                "Container is not provisioned with AllVersionsAndDeletes ChangeFeedPolicy");
        when(container.getFeedRanges()).thenThrow(avadEx);

        CosmosChangeFeedReader reader = new CosmosChangeFeedReader(ProviderId.COSMOS);
        MulticloudDbException ex = assertThrows(MulticloudDbException.class,
                () -> reader.listCursors(container, ADDR));
        // The thrown exception must be the AVAD-normalised one, not the
        // generic INVALID_REQUEST that CosmosErrorMapper would produce.
        assertEquals(MulticloudDbErrorCategory.UNSUPPORTED_CAPABILITY, ex.error().category(),
                "listCursors must surface the AVAD-not-enabled fingerprint as "
                        + "UNSUPPORTED_CAPABILITY, not the generic INVALID_REQUEST");
        assertEquals("avad_not_enabled", ex.error().providerDetails().get("reason"));
        assertEquals("listCursors", ex.error().operation());
        assertSame(avadEx, ex.getCause(),
                "the underlying CosmosException must be preserved as the cause for "
                        + "diagnostic traceability");
    }

    @Test
    @DisplayName("listCursors: CosmosException(400, unrelated msg) → falls through to generic mapper")
    @SuppressWarnings("unchecked")
    void listCursors_integration_unrelated400FallsThrough() {
        CosmosContainer container = mock(CosmosContainer.class);
        // Use a real FeedRange so warmupContinuation reaches the queryChangeFeed
        // call (which we'll make throw the unrelated 400).
        FeedRange range = FeedRange.forFullRange();
        when(container.getFeedRanges()).thenReturn(List.of(range));

        CosmosException badRequest = mock(CosmosException.class);
        when(badRequest.getStatusCode()).thenReturn(400);
        when(badRequest.getMessage()).thenReturn("Document exceeds 2MB size limit");
        when(container.queryChangeFeed(any(CosmosChangeFeedRequestOptions.class), eq(JsonNode.class)))
                .thenThrow(badRequest);

        // Forcing the warmup down the throw path drives the reader into the
        // generic catch — but warmupContinuation absorbs RuntimeExceptions
        // and falls back to the @@PIT: anchor. So this integration test only
        // confirms the no-throw path; the negative-fingerprint logic is
        // covered above by noMatch_unrelated400_returnsNull.
        CosmosChangeFeedReader reader = new CosmosChangeFeedReader(ProviderId.COSMOS);
        // Should complete without exception (warmup absorbs and falls back).
        assertEquals(1, reader.listCursors(container, ADDR).size(),
                "an unrelated 400 thrown from warmup must NOT propagate as "
                        + "UNSUPPORTED_CAPABILITY — warmup absorbs it and falls back to @@PIT");
    }
}
