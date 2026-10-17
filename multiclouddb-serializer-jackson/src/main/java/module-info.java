// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

/** Optional Jackson implementation of the neutral, application-owned DocumentCodec. */
module com.multiclouddb.serializer.jackson {
    exports com.multiclouddb.serializer.jackson;
    requires transitive com.multiclouddb.api;
    requires com.fasterxml.jackson.core;
    requires transitive com.fasterxml.jackson.databind;
}
