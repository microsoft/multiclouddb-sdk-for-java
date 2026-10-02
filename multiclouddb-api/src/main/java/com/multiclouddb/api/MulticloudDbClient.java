// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.api;

import com.multiclouddb.api.changefeed.ChangeFeedCursor;
import com.multiclouddb.api.changefeed.ChangeFeedPage;
import com.multiclouddb.api.changefeed.CursorExpiredException;

import java.util.List;
import java.util.Map;

/**
 * Portable client interface for key-based document operations, query, and
 * capability-gated features across cloud database providers.
 * <p>
 * All operations use a provider-neutral <strong>synchronous</strong> contract.
 * Provider selection is configuration-only — no code changes are required to
 * switch providers. Async APIs are out of scope for v1.
 *
 * <p>
 * There are no code-level escape hatches. Diagnostics and provider-specific
 * opt-ins are controlled via {@link MulticloudDbClientConfig} only.
 */
public interface MulticloudDbClient extends AutoCloseable {

    /**
     * Insert a new document. Fails if a document with the same key already exists.
     * <p>
     * Shared preflight runs before provider I/O. The document must be non-null and
     * serialize as a JSON object. Preflight serialization uses the SDK-owned Jackson
     * configuration; caller-registered modules are not consulted. Convert values requiring
     * custom modules, such as {@link java.time.Instant}, to serializable values first.
     * The supplied top-level {@code Map} entries are snapshotted before
     * validation, so a class-level map serializer cannot add, remove, or rename fields.
     * Cyclic value graphs and non-collection {@link Iterable} values are rejected rather
     * than delegated or traversed without a bound. The bounded normalized snapshot is
     * detached from caller-owned nested values and is the exact document delegated to the
     * provider.
     * <p>
     * Top-level provider-owned names are reserved case-insensitively: {@code id},
     * {@code partitionKey}, {@code sortKey}, {@code ttl}, {@code ttlExpiry}, and
     * {@code data}; names beginning with {@code _} are also reserved. Complete-document
     * top-level names must be unique ignoring case and contain at most
     * 128 Unicode characters.
     * Nested map keys are limited to
     * 50,000 UTF-8 bytes. Binary values are
     * rejected wherever they occur, including inside a POJO.
     * Serialized JSON output is capped while it is produced. Serialized UTF-8 size
     * and provider-neutral structural footprint are independently limited to
     * 390 KiB each, and map/list nesting below the document root is limited to
     * 31 levels.
     * <p>
     * A non-null {@link OperationOptions#ttlSeconds()} is honored only when the
     * provider advertises {@link Capability#ROW_LEVEL_TTL}. Providers without that
     * capability ignore it and store the document without expiry; callers that require
     * expiration must inspect the capability before writing.
     *
     * @param address  target database + collection
     * @param key      document key
     * @param document non-null complete document payload
     * @param options  operation options (timeout, etc.)
     * @throws MulticloudDbException category {@link MulticloudDbErrorCategory#CONFLICT}
     *         if the key already exists, or non-retryable
     *         {@link MulticloudDbErrorCategory#INVALID_REQUEST} if the document is
     *         null, cyclic, cannot be serialized, contains an unbounded iterable,
     *         a provider-reserved top-level field or binary value, or exceeds a field-name,
     *         nesting, serialized-size, or structural-footprint limit
     */
    void create(ResourceAddress address, MulticloudDbKey key, Map<String, Object> document, OperationOptions options);

    /**
     * Insert a new document using default options. Fails if key already exists.
     * See {@link #create(ResourceAddress, MulticloudDbKey, Map, OperationOptions)}
     * for the complete write contract.
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
     * @return the document result after top-level provider-owned fields are removed by the
     *         shared client, or {@code null} if not found; requested metadata remains separate
     */
    DocumentResult read(ResourceAddress address, MulticloudDbKey key, OperationOptions options);

    /**
     * Read a document by key, using default options.
     */
    default DocumentResult read(ResourceAddress address, MulticloudDbKey key) {
        return read(address, key, OperationOptions.defaults());
    }

    /**
     * Apply a portable <strong>shallow, set/replace-only partial update</strong> to an
     * existing document.
     * <p>
     * Each entry in {@code fields} is one literal top-level field. Present fields are
     * set/replaced; omitted fields are preserved; an object or array value replaces that
     * entire top-level value (the merge is shallow, never recursive); a Java {@code null}
     * stores JSON null and does <em>not</em> remove the field. Field names are literal, not
     * path syntax — names exactly {@code .}, {@code /}, and {@code ~} address top-level
     * fields, and an accepted name such as {@code " customer "} is never trimmed. Provider
     * mappings still apply. All requested assignments commit atomically. Replaying the same
     * absolute assignments is idempotent for caller-visible document fields; provider-maintained
     * metadata and TTL timing are not part of that guarantee.
     * <p>
     * Values are inspected and serialized with the SDK-owned Jackson configuration during
     * bounded shared preflight; caller-registered modules are not consulted. Convert values
     * requiring custom modules, such as {@link java.time.Instant}, to serializable values first.
     * The supplied top-level map entries remain authoritative even when its runtime class
     * has a custom Jackson serializer. Cyclic graphs and non-collection {@link Iterable}
     * values are rejected during bounded shared inspection, and serialized JSON output
     * is capped at 390 KiB while it is produced.
     * <p>
     * A missing document is never created: after the requested field set has valid provider
     * mappings, an absent key throws {@link MulticloudDbException} with category
     * {@link MulticloudDbErrorCategory#NOT_FOUND}. To create-or-replace a whole document use
     * {@link #upsert(ResourceAddress, MulticloudDbKey, Map, OperationOptions)} instead;
     * {@code upsert()} creates a missing document, so read-then-upsert is not an atomic
     * guarded replacement. This release has no exact portable atomic full-document
     * replace-if-present equivalent.
     * <p>
     * Shared preflight (before any provider I/O) rejects, as non-retryable
     * {@link MulticloudDbErrorCategory#INVALID_REQUEST}: a null or empty map; any field
     * name that is null, empty, blank, or longer than 50,000 UTF-8 bytes; a name equal
     * (ignoring case) to {@code id}, {@code partitionKey}, {@code sortKey}, {@code ttl},
     * {@code ttlExpiry}, or {@code data}; a name beginning with {@code _}; a non-null
     * {@link OperationOptions#ttlSeconds()} (TTL is supported only by
     * {@code create()}/{@code upsert()}); more than 10 fields in one call; a serialized
     * field map larger than 390 KiB; an incoming structural footprint larger than
     * 390 KiB; a binary value, including one exposed while serializing a POJO;
     * a cyclic graph or non-collection iterable; or a
     * replacement value deeper than 31 map/list containers, counting its top-level
     * container as level 1. Structural footprint includes UTF-8 attribute names plus the
     * native map/list container and element overhead. The 10-field portable bound keeps
     * every accepted update to one atomic native write operation.
     * <p>
     * The operation is available only when the provider advertises
     * {@link Capability#PARTIAL_UPDATE}. Cosmos DB and DynamoDB advertise it in this release.
     * The Spanner provider explicitly does not, so a valid call returns non-retryable
     * {@link MulticloudDbErrorCategory#UNSUPPORTED_CAPABILITY} after shared validation and
     * before provider delegation. Participating providers preserve case-distinct field names
     * as separate literal top-level fields.
     * TTL timing is outside the portable partial-update contract. DynamoDB leaves an
     * existing absolute expiry unchanged, while a Cosmos DB patch advances {@code _ts}
     * and restarts its relative TTL countdown. Until that behavior is normalized, callers
     * requiring a fixed absolute expiry must not use {@code update()} on TTL-bearing items.
     * <p>
     * The base capability guarantees portable behavior only when both the resulting logical
     * document's serialized JSON and its portable structural footprint remain within
     * 390 KiB. Results above either bound are outside this release's portable contract and
     * may succeed or fail according to the provider's native limit; portable callers must
     * not rely on them. Because result size depends on existing state, the SDK does not add
     * a read/merge preflight. A
     * provider-native rejection follows at most one attempted atomic update and is non-retryable
     * {@link MulticloudDbErrorCategory#UNSUPPORTED_CAPABILITY} with a stable
     * {@code providerDetails.reason} and limit details.
     * Native update timeouts are normalized to retryable
     * {@link MulticloudDbErrorCategory#TRANSIENT_FAILURE}: Cosmos DB HTTP 408/410
     * (retaining 410 substatus) and DynamoDB service request timeout or SDK API-call/
     * API-call-attempt timeout equivalents. These mappings are scoped to {@code update()}.
     *
     * @param address  target database + collection
     * @param key      document key identifying an existing document
     * @param fields   literal top-level fields to set/replace; non-null and non-empty
     * @param options  operation options; {@code ttlSeconds} must be null for {@code update()}
     * @throws MulticloudDbException category {@link MulticloudDbErrorCategory#NOT_FOUND} if the
     *         key does not exist, or {@link MulticloudDbErrorCategory#INVALID_REQUEST} for an
     *         invalid field map, name, binary value, cyclic/unbounded value,
     *         update TTL, field count,
     *         nesting depth, or over-size serialized/structural payload;
     *         {@link MulticloudDbErrorCategory#UNSUPPORTED_CAPABILITY} if the provider does
     *         not advertise partial update or a native envelope rejects the request; or
     *         {@link MulticloudDbErrorCategory#TRANSIENT_FAILURE} for a retryable
     *         provider timeout
     */
    void update(ResourceAddress address, MulticloudDbKey key, Map<String, Object> fields, OperationOptions options);

    /**
     * Apply a shallow partial update using default options. Fails with {@code NOT_FOUND} if
     * the key does not exist. See
     * {@link #update(ResourceAddress, MulticloudDbKey, Map, OperationOptions)} for the full
     * partial-update contract.
     */
    default void update(ResourceAddress address, MulticloudDbKey key, Map<String, Object> fields) {
        update(address, key, fields, OperationOptions.defaults());
    }

    /**
     * Create or fully replace the document identified by {@code key}.
     * <p>
     * This is create-or-full-replace, not an atomic replace-if-present operation. A
     * missing item is created. A read-then-upsert sequence can recreate an item deleted
     * or expired between calls, and this release has no exact portable atomic
     * full-document replace-if-present equivalent.
     * <p>
     * Shared preflight runs before provider I/O. The document must be non-null and
     * serialize as a JSON object. Preflight serialization uses the SDK-owned Jackson
     * configuration; caller-registered modules are not consulted. Convert values requiring
     * custom modules, such as {@link java.time.Instant}, to serializable values first.
     * The supplied top-level {@code Map} entries are snapshotted before
     * validation, so a class-level map serializer cannot add, remove, or rename fields.
     * Cyclic value graphs and non-collection {@link Iterable} values are rejected rather
     * than delegated or traversed without a bound. The bounded normalized snapshot is
     * detached from caller-owned nested values and is the exact document delegated to the
     * provider.
     * <p>
     * Top-level provider-owned names are reserved case-insensitively: {@code id},
     * {@code partitionKey}, {@code sortKey}, {@code ttl}, {@code ttlExpiry}, and
     * {@code data}; names beginning with {@code _} are also reserved. Complete-document
     * top-level names must be unique ignoring case and contain at most
     * 128 Unicode characters.
     * Nested map keys are limited to
     * 50,000 UTF-8 bytes. Binary values are
     * rejected wherever they occur, including inside a POJO.
     * Serialized JSON output is capped while it is produced. Serialized UTF-8 size
     * and provider-neutral structural footprint are independently limited to
     * 390 KiB each, and map/list nesting below the document root is limited to
     * 31 levels.
     * <p>
     * A non-null {@link OperationOptions#ttlSeconds()} is honored only when the
     * provider advertises {@link Capability#ROW_LEVEL_TTL}. Providers without that
     * capability ignore it and store the document without expiry; callers that require
     * expiration must inspect the capability before writing.
     *
     * @param address  target database + collection
     * @param key      document key
     * @param document non-null complete replacement document
     * @param options  operation options (timeout, etc.)
     * @throws MulticloudDbException non-retryable
     *         {@link MulticloudDbErrorCategory#INVALID_REQUEST} if the document is
     *         null, cyclic, cannot be serialized, contains an unbounded iterable,
     *         a provider-reserved top-level field or binary value, or exceeds a field-name,
     *         nesting, serialized-size, or structural-footprint limit
     */
    void upsert(ResourceAddress address, MulticloudDbKey key, Map<String, Object> document, OperationOptions options);

    /**
     * Upsert (create or replace) a document identified by key, using default
     * options. See
     * {@link #upsert(ResourceAddress, MulticloudDbKey, Map, OperationOptions)}
     * for the complete write and migration contract.
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
     * a missing key, but it applies a shallow partial update to the named fields
     * on hit, so it mutates state and is not a safe pure existence probe.
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
     * @return a page whose items have top-level provider-owned fields removed by the
     *         shared client, with an optional continuation token
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
