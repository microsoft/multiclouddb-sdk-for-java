// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.provider.cosmos;

import com.azure.cosmos.models.FeedResponse;
import com.azure.cosmos.util.CosmosPagedIterable;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The portable contract hands back one page at a time, so every Cosmos read abandons its paged
 * result after the first page. These tests pin the two properties that makes safe: the page we
 * return is the first one, and the underlying subscription is always cancelled — including when
 * the result set is empty and when the query throws.
 */
class CosmosPagedReaderTest {

    @Test
    @DisplayName("reading one page cancels the subscription instead of leaving it prefetching")
    @SuppressWarnings("unchecked")
    void readingOnePageClosesTheStream() {
        AtomicBoolean closed = new AtomicBoolean(false);
        FeedResponse<JsonNode> first = mock(FeedResponse.class);
        FeedResponse<JsonNode> second = mock(FeedResponse.class);

        CosmosPagedIterable<JsonNode> paged = mock(CosmosPagedIterable.class);
        when(paged.streamByPage(anyInt()))
                .thenAnswer(inv -> Stream.of(first, second).onClose(() -> closed.set(true)));

        FeedResponse<JsonNode> page = CosmosPagedReader.firstPage(paged, null, 12);

        assertSame(first, page, "the caller must receive the first page");
        assertTrue(closed.get(),
                "the stream must be closed so reactor cancels the subscription; otherwise Cosmos "
                        + "keeps prefetching pages the caller can never reach");
    }

    @Test
    @DisplayName("pages beyond the first are never pulled")
    @SuppressWarnings("unchecked")
    void laterPagesAreNotConsumed() {
        AtomicInteger pulled = new AtomicInteger();
        FeedResponse<JsonNode> first = mock(FeedResponse.class);
        FeedResponse<JsonNode> second = mock(FeedResponse.class);

        CosmosPagedIterable<JsonNode> paged = mock(CosmosPagedIterable.class);
        when(paged.streamByPage(anyInt()))
                .thenAnswer(inv -> Stream.of(first, second).peek(p -> pulled.incrementAndGet()));

        CosmosPagedReader.firstPage(paged, null, 12);

        assertEquals(1, pulled.get(), "only the page we return may be pulled off the stream");
    }

    @Test
    @DisplayName("a continuation token resumes rather than restarting")
    @SuppressWarnings("unchecked")
    void continuationTokenSelectsTheResumingOverload() {
        FeedResponse<JsonNode> resumed = mock(FeedResponse.class);
        CosmosPagedIterable<JsonNode> paged = mock(CosmosPagedIterable.class);
        when(paged.streamByPage(anyString(), anyInt())).thenAnswer(inv -> Stream.of(resumed));
        when(paged.streamByPage(anyInt()))
                .thenThrow(new AssertionError("must resume from the token, not restart"));

        assertSame(resumed, CosmosPagedReader.firstPage(paged, "token-abc", 12));
    }

    @Test
    @DisplayName("an empty result set yields null and still closes the stream")
    @SuppressWarnings("unchecked")
    void emptyResultSetStillCloses() {
        AtomicBoolean closed = new AtomicBoolean(false);
        CosmosPagedIterable<JsonNode> paged = mock(CosmosPagedIterable.class);
        when(paged.streamByPage(anyInt()))
                .thenAnswer(inv -> Stream.<FeedResponse<JsonNode>>empty().onClose(() -> closed.set(true)));

        assertNull(CosmosPagedReader.firstPage(paged, null, 12));
        assertTrue(closed.get(), "an empty result set still holds a subscription that must be released");
    }

    @Test
    @DisplayName("a failure mid-page still releases the subscription")
    @SuppressWarnings("unchecked")
    void failureStillCloses() {
        AtomicBoolean closed = new AtomicBoolean(false);
        CosmosPagedIterable<JsonNode> paged = mock(CosmosPagedIterable.class);
        when(paged.streamByPage(anyInt())).thenAnswer(inv ->
                Stream.<FeedResponse<JsonNode>>generate(() -> {
                    throw new IllegalStateException("boom");
                }).onClose(() -> closed.set(true)));

        assertThrows(IllegalStateException.class, () -> CosmosPagedReader.firstPage(paged, null, 12));
        assertTrue(closed.get(), "a failed query must not leak the pipeline it was using");
    }

    @Test
    @DisplayName("the change-feed overload reads one page and closes it too")
    @SuppressWarnings("unchecked")
    void changeFeedOverloadClosesTheStream() {
        AtomicBoolean closed = new AtomicBoolean(false);
        FeedResponse<JsonNode> first = mock(FeedResponse.class);
        CosmosPagedIterable<JsonNode> paged = mock(CosmosPagedIterable.class);
        when(paged.streamByPage())
                .thenAnswer(inv -> Stream.of(first).onClose(() -> closed.set(true)));

        assertSame(first, CosmosPagedReader.firstPage(paged));
        assertTrue(closed.get(), "the change feed polls in a loop, so a leak per poll compounds fastest here");
    }
}