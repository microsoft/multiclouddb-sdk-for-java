// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.api;

import com.multiclouddb.api.changefeed.ChangeFeedCursor;
import com.multiclouddb.api.changefeed.ChangeFeedPage;
import com.multiclouddb.api.changefeed.CursorExpiredException;

import java.util.List;
import java.util.Map;

/**
 * Portable client interface for CRUD + query operations across cloud database
 * providers.
 * <p>
 * All operations use a provider-neutral <strong>synchronous</strong> contract.
 * Provider selection is configuration-only — no code changes are required to
 * switch providers. Async APIs are out of scope for v1.
 * <p>
 * There are no code-level escape hatches. Diagnostics and provider-specific
 * opt-ins are controlled via {@link MulticloudDbClientConfig} only.
 */
public interface MulticloudDbClient extends AutoCloseable {

    /**
     * Insert a new document. Fails if a document with the same key already exists.
     *
     * @param address  target database + collection
     * @param key      document key
     * @param document document payload
     * @param options  operation options (timeout, etc.)
     * @throws MulticloudDbException with category CONFLICT if the key already exists
     */
    void create(ResourceAddress address, MulticloudDbKey key, Map<String, Object> document, OperationOptions options);

    /**
     * Insert a new document using default options. Fails if key already exists.
     */
    default void create(ResourceAddress address, MulticloudDbKey key, Map<String, Object> document) {
        create(address, key, document, OperationOptions.defaults());
    }

    /**
     * Read a document by key.
     *
     * @param address target database + collection
     * @param key     document key
     * @param options operation options; set {@link OperationOptions#includeMetadata()} to
     *                {@code true} to request provider write-metadata
     * @return the document result (document + optional metadata), or {@code null} if not found
     */
    DocumentResult read(ResourceAddress address, MulticloudDbKey key, OperationOptions options);

    /**
     * Read a document by key, using default options.
     */
    default DocumentResult read(ResourceAddress address, MulticloudDbKey key) {
        return read(address, key, OperationOptions.defaults());
    }

    /**
     * Update an existing document. Fails if a document with the given key does not
     * exist.
     *
     * @param address  target database + collection
     * @param key      document key
     * @param document document payload
     * @param options  operation options
     * @throws MulticloudDbException with category NOT_FOUND if the key does not exist
     */
    void update(ResourceAddress address, MulticloudDbKey key, Map<String, Object> document, OperationOptions options);

    /**
     * Update an existing document using default options. Fails if key does not
     * exist.
     */
    default void update(ResourceAddress address, MulticloudDbKey key, Map<String, Object> document) {
        update(address, key, document, OperationOptions.defaults());
    }

    /**
     * Upsert (create or replace) a document identified by key.
     *
     * @param address  target database + collection
     * @param key      document key
     * @param document document payload
     * @param options  operation options (timeout, etc.)
     */
    void upsert(ResourceAddress address, MulticloudDbKey key, Map<String, Object> document, OperationOptions options);

    /**
     * Upsert (create or replace) a document identified by key, using default
     * options.
     */
    default void upsert(ResourceAddress address, MulticloudDbKey key, Map<String, Object> document) {
        upsert(address, key, document, OperationOptions.defaults());
    }

    /**
     * Delete a document by key.
     * <p>
     * Idempotent: deleting a key that does not exist is a silent no-op on every
     * provider. This is the LCD across Cosmos (404 swallowed), DynamoDB
     * ({@code DeleteItem} naturally no-ops) and Spanner ({@code Mutation.delete}
     * naturally no-ops).
     * <p>
     * Callers that need to detect whether a key exists should use
     * {@link #read(ResourceAddress, MulticloudDbKey, OperationOptions)} — it
     * returns {@code null} on every provider when the key does not exist, and
     * does not mutate state. {@code update()} also throws {@code NOT_FOUND} on
     * a missing key, but it requires a document body and <strong>overwrites</strong>
     * the existing document on hit, so it is not a safe pure existence probe.
     *
     * @param address target database + collection
     * @param key     document key
     * @param options operation options
     * @throws MulticloudDbException for any provider error; a missing key is
     *         silently ignored and does not throw
     */
    void delete(ResourceAddress address, MulticloudDbKey key, OperationOptions options);

    /**
     * Delete a document by key, using default options.
     */
    default void delete(ResourceAddress address, MulticloudDbKey key) {
        delete(address, key, OperationOptions.defaults());
    }

    /**
     * Execute a query and return a single page of results.
     *
     * @param address target database + collection
     * @param query   query request (expression, parameters, page size, continuation
     *                token)
     * @param options operation options
     * @return a page of results with optional continuation token
     */
    QueryPage query(ResourceAddress address, QueryRequest query, OperationOptions options);

    /**
     * Execute a query using default options.
     */
    default QueryPage query(ResourceAddress address, QueryRequest query) {
        return query(address, query, OperationOptions.defaults());
    }

    /**
     * Discover capabilities supported by the current provider.
     */
    CapabilitySet capabilities();

    /**
     * Ensure a logical database exists, creating it if it does not already exist.
     * <p>
     * This is an idempotent operation — if the database already exists the call
     * succeeds silently. Use this at application startup to guarantee the required
     * databases are in place before performing data operations.
     * <p>
     * For providers without a native database concept (e.g., DynamoDB), this is a
     * no-op.
     * <p>
     * <b>Permission note:</b> this operation uses each provider's standard
     * data-plane SDK and is subject to the caller's runtime permissions. When
     * the caller lacks sufficient permissions (e.g., Cosmos DB data-plane RBAC
     * without a control-plane role), the SDK throws a
     * {@link MulticloudDbException} with category {@code PERMISSION_DENIED}.
     * Provision the database out-of-band (portal, CLI, IaC) if needed.
     *
     * @param database the logical database name to create if absent
     * @throws MulticloudDbException with category {@code PERMISSION_DENIED} when
     *                               the caller lacks permissions, or
     *                               {@code CONFLICT} / {@code INTERNAL_ERROR} for
     *                               other failures
     */
    void ensureDatabase(String database);

    /**
     * Ensure a container (table) exists within the given database, creating it if
     * it does not already exist.
     * <p>
     * This is an idempotent operation — if the container already exists the call
     * succeeds silently. Use this at application startup to guarantee the required
     * containers are in place before performing data operations.
     * <p>
     * Containers are always created with the SDK's standard schema conventions
     * (partition key path {@code /partitionKey}, sort key column {@code sortKey}).
     *
     * @param address the database + collection identifying the container to create
     *                if absent
     * @throws MulticloudDbException if the creation fails for a reason other than
     *                               the resource already existing
     */
    void ensureContainer(ResourceAddress address);

    /**
     * Provision a full schema of databases and containers in a single call.
     * <p>
     * Equivalent to calling {@link #ensureDatabase} for every database key and
     * {@link #ensureContainer} for every collection, but executes both phases in
     * parallel using a bounded thread pool (max 10 threads) for efficiency.
     * <p>
     * All operations are idempotent — existing resources are left unchanged.
     * Use this at application startup to guarantee the entire required schema is
     * in place before performing data operations.
     *
     * @param schema map of database name → list of collection/table names to ensure
     * @throws MulticloudDbException if any database or container creation fails
     */
    void provisionSchema(java.util.Map<String, java.util.List<String>> schema);

    // ── Change Feed ────────────────────────────────────────────────────────────
    // Portable pull-based change feed across Cosmos, DynamoDB, and Spanner.
    // See docs/guide.md "Change Feeds" chapter for the multi-thread patterns
    // these primitives compose into. Three primitives, no inversion of control,
    // no managed parallelism in v1.

    /**
     * Discover the current live partitions of the change feed for {@code address}.
     * <p>
     * Returns one {@link ChangeFeedCursor} per provider-side partition that exists
     * <em>at the moment of the call</em>, each positioned at the live tip of its
     * partition — no events that occurred before {@code listCursors} returns are
     * surfaced.
     * <p>
     * <b>This is the partition-discovery primitive.</b> Distribute the returned
     * cursors across worker threads / processes for parallel consumption. Re-call
     * periodically to detect topology changes — new cursors appear after a split,
     * existing cursors go {@linkplain ChangeFeedPage#isTerminal() terminal} after
     * a merge.
     * <p>
     * Cursors are <em>independent</em> — concurrent {@code readChanges} calls on
     * different cursors do not interfere. Do not, however, share a single cursor
     * across threads.
     *
     * @param address target database + collection
     * @return one cursor per partition; never {@code null}, never empty for a
     *         provider that has provisioned change feed; ordered as the
     *         provider reports partitions (no portable ordering guarantee).
     * @throws MulticloudDbException with category
     *         {@link MulticloudDbErrorCategory#UNSUPPORTED_CAPABILITY} if the
     *         provider does not support change feed (see
     *         {@link Capability#CHANGE_FEED}); other categories for transient or
     *         permanent provider failures.
     */
    List<ChangeFeedCursor> listCursors(ResourceAddress address);

    /**
     * Read one page of change events from the given cursor.
     * <p>
     * Each call returns a {@link ChangeFeedPage} containing zero or more
     * {@link com.multiclouddb.api.changefeed.ChangeEvent}s plus a forward-only
     * {@link ChangeFeedPage#nextCursor() nextCursor} that you must use for the
     * next call. The token encoded inside {@code nextCursor} has a fresh
     * issued-at timestamp — the client-side 24-hour age clock resets on every
     * successful page, so a continuously reading worker never observes the
     * client-side expiry.
     * <p>
     * Provider-side topology changes are absorbed transparently inside this
     * call where the provider supports it — a worker holding a cursor across a
     * split continues to receive events from <em>all</em> child partitions
     * through the returned {@code nextCursor}. Re-call {@link #listCursors} to
     * <em>gain</em> parallelism after a split (the children become distinct
     * cursors).
     *
     * @param address target database + collection (must match the
     *                cursor's resource binding, if any)
     * @param cursor  the cursor to read from. For a freshly minted
     *                {@link ChangeFeedCursor#now()} sentinel, the SDK starts at
     *                the live tip of {@code address} and binds the returned
     *                {@code nextCursor} to {@code address}.
     * @return a page; never {@code null}.
     * @throws CursorExpiredException if the cursor's token is older than the
     *         24-hour portable baseline, the provider has trimmed the cursor's
     *         events, or the cursor was minted for a different provider or
     *         resource.
     * @throws MulticloudDbException with category
     *         {@link MulticloudDbErrorCategory#UNSUPPORTED_CAPABILITY} if the
     *         provider does not support change feed; other categories for
     *         transient or permanent provider failures.
     */
    ChangeFeedPage readChanges(ResourceAddress address, ChangeFeedCursor cursor);

    /**
     * Read one page of change events using the supplied operation options.
     * <p>
     * <b>v1 note:</b> no built-in provider currently honours any field of
     * {@code options} on the change-feed path — {@link OperationOptions#timeout()}
     * in particular is <em>not</em> enforced. The parameter exists for forward
     * compatibility (so providers can opt into per-page timeouts or other
     * controls without an SPI change), and so callers can mint a single
     * {@link OperationOptions} value reused across the CRUD and change-feed
     * surfaces. Pass {@link OperationOptions#defaults()} if you have no
     * specific request to make.
     */
    ChangeFeedPage readChanges(ResourceAddress address, ChangeFeedCursor cursor,
                               OperationOptions options);

    /**
     * Get the provider ID for this client.
     */
    ProviderId providerId();
}
