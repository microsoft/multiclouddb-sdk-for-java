// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.api.changefeed;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.multiclouddb.api.MulticloudDbKey;
import com.multiclouddb.api.ProviderId;
import com.multiclouddb.api.ResourceAddress;

import java.time.Instant;
import java.util.Objects;

/**
 * A single change emitted by a provider's change feed.
 * <p>
 * Identity and dedup: {@link #eventId()} is a provider-stable string —
 * Cosmos {@code _lsn}/{@code _etag}, Dynamo {@code SequenceNumber}, Spanner
 * {@code commit_timestamp + record_sequence}. Consumers MUST dedupe by
 * {@code (providerId, eventId)} because change-feed delivery is at-least-once.
 *
 * <p>Payload: {@link #data()} carries the new image of the document when the
 * provider can supply one and the {@link NewItemStateMode} of the request was
 * not {@link NewItemStateMode#OMIT}. {@code null} for delete events on
 * providers that do not return tombstones with the prior image.
 */
public final class ChangeEvent {

    private final ProviderId provider;
    private final String eventId;
    private final ChangeType eventType;
    private final ResourceAddress address;
    private final MulticloudDbKey key;
    private final ObjectNode data;
    private final Instant commitTimestamp;

    public ChangeEvent(
            ProviderId provider,
            String eventId,
            ChangeType eventType,
            ResourceAddress address,
            MulticloudDbKey key,
            ObjectNode data,
            Instant commitTimestamp) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.eventId = Objects.requireNonNull(eventId, "eventId");
        this.eventType = Objects.requireNonNull(eventType, "eventType");
        this.address = Objects.requireNonNull(address, "address");
        this.key = Objects.requireNonNull(key, "key");
        this.data = data;
        this.commitTimestamp = commitTimestamp;
    }

    public ProviderId provider() { return provider; }
    public String eventId() { return eventId; }
    public ChangeType eventType() { return eventType; }
    public ResourceAddress address() { return address; }
    public MulticloudDbKey key() { return key; }

    /** New image of the document when available, otherwise {@code null}. */
    public ObjectNode data() { return data; }

    /** Commit timestamp when supplied by the provider, otherwise {@code null}. */
    public Instant commitTimestamp() { return commitTimestamp; }

    @Override
    public String toString() {
        return "ChangeEvent{" + provider.id() + ":" + eventType + " " + address + " key=" + key
                + " eventId=" + eventId
                + (commitTimestamp != null ? " ts=" + commitTimestamp : "")
                + (data != null ? " data=<present>" : "")
                + "}";
    }
}
