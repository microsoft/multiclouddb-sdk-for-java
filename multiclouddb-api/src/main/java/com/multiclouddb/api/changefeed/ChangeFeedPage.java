// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.api.changefeed;

import com.multiclouddb.api.OperationDiagnostics;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * One page of change events produced by
 * {@link com.multiclouddb.api.MulticloudDbClient#readChanges(
 * com.multiclouddb.api.ResourceAddress, ChangeFeedCursor) readChanges}.
 * <p>
 * A page is a snapshot, not a stream: it carries zero or more
 * {@link ChangeEvent}s plus a forward-only {@link #nextCursor()} that you must
 * use for the next call. Tokens are refreshed every page — the issued-at
 * timestamp encoded inside {@link #nextCursor()} resets each time, so an
 * actively reading worker never observes the 24-hour client-side age-out.
 *
 * <h3>Pagination contract</h3>
 * <ul>
 *   <li>{@link #hasMore()} == {@code true} — the SDK believes more events are
 *       <em>immediately</em> available (the page hit its provider-side size
 *       cap, for example). Re-call {@code readChanges} with
 *       {@link #nextCursor()} without delay.</li>
 *   <li>{@link #hasMore()} == {@code false} — the cursor has caught up to
 *       the live tip. Back off before the next call (see the multi-thread
 *       patterns in {@code docs/guide.md}).</li>
 *   <li>{@link #isTerminal()} == {@code true} — the cursor's partition has
 *       been merged out of existence (Spanner) or the provider otherwise
 *       reports no successor. {@link #nextCursor()} on a terminal page is
 *       still non-null but will keep returning empty terminal pages; drop the
 *       cursor and re-call
 *       {@link com.multiclouddb.api.MulticloudDbClient#listCursors(
 *       com.multiclouddb.api.ResourceAddress) listCursors} to rebalance.</li>
 * </ul>
 *
 * <h3>Persistence rule</h3>
 * Persist {@link #nextCursor()}{@code .toToken()} <em>after</em> processing
 * the events. Persisting before {@code process()} returns risks losing events
 * on crash. This is the at-least-once contract.
 */
public final class ChangeFeedPage {

    private final List<ChangeEvent> events;
    private final ChangeFeedCursor nextCursor;
    private final boolean hasMore;
    private final boolean terminal;
    private final OperationDiagnostics diagnostics;

    public ChangeFeedPage(List<ChangeEvent> events,
                          ChangeFeedCursor nextCursor,
                          boolean hasMore,
                          boolean terminal) {
        this(events, nextCursor, hasMore, terminal, null);
    }

    public ChangeFeedPage(List<ChangeEvent> events,
                          ChangeFeedCursor nextCursor,
                          boolean hasMore,
                          boolean terminal,
                          OperationDiagnostics diagnostics) {
        this.events = events != null ? List.copyOf(events) : Collections.emptyList();
        this.nextCursor = Objects.requireNonNull(nextCursor, "nextCursor");
        if (hasMore && terminal) {
            throw new IllegalArgumentException(
                    "ChangeFeedPage cannot be both terminal and hasMore=true");
        }
        this.hasMore = hasMore;
        this.terminal = terminal;
        this.diagnostics = diagnostics;
    }

    /**
     * Events in this page, in provider partition order. Unmodifiable.
     */
    public List<ChangeEvent> events() {
        return events;
    }

    /**
     * Forward-only cursor to use on the next {@code readChanges} call.
     * Never {@code null}. The encoded token's issued-at is fresh; the 24-hour
     * client-side age clock resets each time the SDK successfully issues a page.
     */
    public ChangeFeedCursor nextCursor() {
        return nextCursor;
    }

    /**
     * {@code true} iff the SDK believes more events are immediately available
     * on the cursor's partition(s). When {@code false}, the cursor is at the
     * live tip and the caller should back off before the next call.
     */
    public boolean hasMore() {
        return hasMore;
    }

    /**
     * {@code true} iff the cursor's partition has merged out of existence on
     * the provider side. The cursor will keep returning empty terminal pages;
     * drop it and re-call {@code listCursors}.
     */
    public boolean isTerminal() {
        return terminal;
    }

    /**
     * Per-page diagnostics (duration, item count, provider request id, etc.),
     * or {@code null} if diagnostics are not available.
     */
    public OperationDiagnostics diagnostics() {
        return diagnostics;
    }

    @Override
    public String toString() {
        return "ChangeFeedPage{events=" + events.size()
                + ", hasMore=" + hasMore
                + ", terminal=" + terminal + '}';
    }
}
