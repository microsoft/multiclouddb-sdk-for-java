// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.provider.cosmos;

import com.azure.cosmos.models.FeedResponse;
import com.azure.cosmos.util.CosmosPagedIterable;

import java.util.stream.Stream;

/**
 * Reads exactly one page from a Cosmos paged result and cancels the rest of the work.
 * <p>
 * Every portable read that Cosmos serves — {@code query}, {@code queryWithTranslation}, and the
 * change-feed drain — is <b>page-at-a-time by contract</b>: the caller receives one page plus a
 * continuation token and re-issues the request to advance. The next request builds a brand-new
 * {@link CosmosPagedIterable} from that token, so <b>any page the Cosmos pipeline prefetches
 * behind the one we return is unreachable forever</b>. It is executed, billed in RU, and thrown
 * away.
 * <p>
 * That waste is not hypothetical. {@code CosmosPagedIterable.iterableByPage(...)} is backed by a
 * reactive subscription ({@code CosmosPagedFlux} is not a {@code ContinuablePagedFluxCore}, so
 * azure-core routes it through {@code Flux.toIterable}). That subscription is cancelled only when
 * the iterator is drained, so taking one page and abandoning the iterator — the shape every call
 * site needs — leaks one live query pipeline per call. Under sustained load the leaked pipelines
 * accumulate: a large-document query workload was measured climbing from 16 ms to 165 ms mean
 * latency over a single run while server-side RU per page stayed constant, with the post-GC live
 * set growing from 191 MB to 549 MB.
 * <p>
 * {@link CosmosPagedIterable#streamByPage()} exposes the same pages through a {@link Stream} whose
 * {@link Stream#close()} cancels that subscription (reactor registers the cancel hook via
 * {@code onClose}). Closing it here stops the prefetch as soon as the page is in hand. Providers
 * that page with a single stateless request — DynamoDB's {@code lastEvaluatedKey} and Spanner's
 * {@code LIMIT/OFFSET} — never had the equivalent exposure, so this restores cost parity rather
 * than changing observable behaviour: the items, the continuation token, and the diagnostics are
 * identical either way.
 */
final class CosmosPagedReader {

    private CosmosPagedReader() {
    }

    /**
     * Reads the first page of an unparameterised paged result (used by the change feed, which
     * carries its page size on the request options rather than the iterable).
     *
     * @param paged the Cosmos paged result to read one page from
     * @param <T>   the item type of the feed
     * @return the first page, or {@code null} when the result set is empty
     */
    static <T> FeedResponse<T> firstPage(CosmosPagedIterable<T> paged) {
        return firstOf(paged.streamByPage());
    }

    /**
     * Reads the first page of a query result, resuming from {@code continuationToken} when one is
     * supplied.
     *
     * @param paged             the Cosmos paged result to read one page from
     * @param continuationToken the token to resume from, or {@code null} to start at the beginning
     * @param pageSize          the preferred page size sent to Cosmos as the max item count
     * @param <T>               the item type of the feed
     * @return the first page, or {@code null} when the result set is empty
     */
    static <T> FeedResponse<T> firstPage(CosmosPagedIterable<T> paged, String continuationToken, int pageSize) {
        return continuationToken != null
                ? firstOf(paged.streamByPage(continuationToken, pageSize))
                : firstOf(paged.streamByPage(pageSize));
    }

    private static <T> FeedResponse<T> firstOf(Stream<FeedResponse<T>> pages) {
        // try-with-resources is the whole point: close() is what cancels the subscription, and it
        // must run on the exception path too or a failed query leaks the pipeline it was using.
        try (Stream<FeedResponse<T>> closeable = pages) {
            return closeable.findFirst().orElse(null);
        }
    }
}