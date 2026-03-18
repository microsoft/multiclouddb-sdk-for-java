// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.hyperscaledb.api;

import java.util.Objects;

/**
 * Portable addressing of a target logical storage location.
 * Identifies a database (or top-level namespace) and a collection
 * (container/table).
 */
public final class ResourceAddress {

    private final String database;
    private final String collection;

    public ResourceAddress(String database, String collection) {
        if (database == null || database.isBlank()) {
            throw new IllegalArgumentException("database must be non-empty");
        }
        if (collection == null || collection.isBlank()) {
            throw new IllegalArgumentException("collection must be non-empty");
        }
        this.database = database;
        this.collection = collection;
    }

    public String database() {
        return database;
    }

    public String collection() {
        return collection;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (!(o instanceof ResourceAddress that))
            return false;
        return database.equals(that.database) && collection.equals(that.collection);
    }

    @Override
    public int hashCode() {
        return Objects.hash(database, collection);
    }

    @Override
    public String toString() {
        return database + "/" + collection;
    }
}
