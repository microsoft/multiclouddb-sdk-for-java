// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.hyperscaledb.api;

/**
 * Identifies a supported cloud database provider.
 */
public enum ProviderId {
    COSMOS("cosmos", "Azure Cosmos DB"),
    DYNAMO("dynamo", "AWS DynamoDB"),
    SPANNER("spanner", "Google Cloud Spanner");

    private final String id;
    private final String displayName;

    ProviderId(String id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    /**
     * Resolve a ProviderId from its string id (case-insensitive).
     */
    public static ProviderId fromId(String id) {
        for (ProviderId p : values()) {
            if (p.id.equalsIgnoreCase(id)) {
                return p;
            }
        }
        throw new IllegalArgumentException("Unknown provider id: " + id);
    }
}
