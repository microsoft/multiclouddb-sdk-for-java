// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.api.changefeed;

/**
 * The kind of mutation that produced a {@link ChangeEvent}.
 * <p>
 * The portable contract surfaces three event kinds. CREATE / UPDATE distinction
 * and DELETE detection both depend on provider-side configuration that the SDK
 * does not auto-perform on {@link com.multiclouddb.api.MulticloudDbClient#ensureContainer}.
 * See the <em>Change Feeds</em> chapter of {@code docs/guide.md} for the
 * required provisioning per provider (Cosmos AVAD mode, DynamoDB Streams
 * {@code NEW_AND_OLD_IMAGES}, Spanner change-streams with
 * {@code value_capture_type = 'NEW_ROW'}).
 *
 * @see ChangeEvent#type()
 */
public enum ChangeType {

    /** The event represents an item that did not exist before the mutation. */
    CREATE,

    /** The event represents an item whose value was replaced. */
    UPDATE,

    /**
     * The event represents an item that was removed. For DELETE events,
     * {@link ChangeEvent#data()} is typically {@code null} — providers do not
     * portably guarantee a pre-image.
     */
    DELETE
}
