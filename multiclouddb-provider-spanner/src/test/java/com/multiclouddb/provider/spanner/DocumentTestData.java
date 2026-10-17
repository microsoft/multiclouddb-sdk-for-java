// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.provider.spanner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiclouddb.api.document.Document;
import java.util.Map;

final class DocumentTestData {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private DocumentTestData() {
    }

    static Document document(Map<String, Object> fields) {
        return fields == null ? null : NativeDocuments.document(MAPPER.valueToTree(fields));
    }
}
