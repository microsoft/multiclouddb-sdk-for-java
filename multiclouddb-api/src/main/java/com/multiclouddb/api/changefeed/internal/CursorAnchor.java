// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.api.changefeed.internal;

/**
 * The starting anchor encoded in a {@link CursorToken}. Determines how a
 * provider hydrates a not-yet-read cursor into a concrete read position.
 *
 * <ul>
 *   <li>{@link #NOW} — start at the live tip of the entire collection (or of
 *       the specific partition for a cursor returned by {@code listCursors}).
 *       Used by {@code ChangeFeedCursor.now()}.</li>
 *   <li>{@link #BEGINNING_OF_RANGE} — start from the earliest available
 *       position in the partition. Used internally by providers that bootstrap
 *       discovery; not exposed on the v1 public API.</li>
 *   <li>{@link #CONTINUING} — the token already records concrete provider-side
 *       positions in {@link CursorToken#partitions()}. Resume from those.</li>
 * </ul>
 */
public enum CursorAnchor {
    NOW,
    BEGINNING_OF_RANGE,
    CONTINUING
}
