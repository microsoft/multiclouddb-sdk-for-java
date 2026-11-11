// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.api;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class QueryPageTest {
    @Test
    void keepsShallowReadonlyRowsWithNullAndMutableNestedValues() {
        List<Object> nested = new ArrayList<>(List.of("value"));
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("nested", nested);
        row.put("nullValue", null);
        List<Map<String, Object>> input = new ArrayList<>(List.of(row));
        QueryPage page = new QueryPage(input, "");
        input.clear();
        row.clear();
        assertEquals(1, page.items().size());
        assertTrue(page.items().get(0).containsKey("nullValue"));
        assertNull(page.items().get(0).get("nullValue"));
        assertThrows(UnsupportedOperationException.class, () -> page.items().clear());
        assertThrows(UnsupportedOperationException.class, () -> page.items().get(0).clear());
        assertSame(nested, page.items().get(0).get("nested"));
        nested.clear();
        assertEquals(List.of(), page.items().get(0).get("nested"));
        assertFalse(page.hasMore());
    }
}
