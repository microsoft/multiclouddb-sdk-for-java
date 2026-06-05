// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.api.changefeed;

/**
 * The type of change represented by a {@link ChangeEvent}.
 * <p>
 * Three values:
 * <ul>
 *   <li>{@link #CREATE} — the document was newly created at this commit.</li>
 *   <li>{@link #UPDATE} — the document existed before and was modified.</li>
 *   <li>{@link #DELETE} — the document was removed.</li>
 * </ul>
 *
 * <p><b>Cosmos portability note:</b> by default Cosmos's change feed
 * <em>conflates</em> CREATE and UPDATE into a single "current document" event.
 * To receive distinct CREATE/UPDATE/DELETE events the container must be
 * configured with change-feed mode {@code AllVersionsAndDeletes} (see
 * {@code docs/configuration.md}). The SDK validates this at the first
 * {@code readChanges()} call and fails with
 * {@link com.multiclouddb.api.MulticloudDbErrorCategory#INVALID_REQUEST} when
 * the container is in the default mode.
 */
public enum ChangeType {
    CREATE,
    UPDATE,
    DELETE
}
