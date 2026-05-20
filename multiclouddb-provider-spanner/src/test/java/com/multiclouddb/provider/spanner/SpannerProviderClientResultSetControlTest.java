// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.provider.spanner;

import com.multiclouddb.api.QueryRequest;
import com.multiclouddb.api.SortDirection;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SpannerProviderClientResultSetControlTest {

    @Test
    void appendsOrderByWhenSqlDoesNotContainOrderBy() throws Exception {
        String sql = "SELECT * FROM Users";
        QueryRequest query = QueryRequest.builder()
                .orderBy("name", SortDirection.ASC)
                .build();

        assertEquals("SELECT * FROM Users ORDER BY name ASC", appendResultSetControl(sql, query));
    }

    @Test
    void doesNotAppendOrderByWhenSqlAlreadyContainsOrderBy() throws Exception {
        String sql = "SELECT * FROM Users ORDER BY createdAt DESC";
        QueryRequest query = QueryRequest.builder()
                .orderBy("name", SortDirection.ASC)
                .build();

        assertEquals(sql, appendResultSetControl(sql, query));
    }

    @Test
    void detectsOrderByCaseInsensitively() throws Exception {
        String sql = "SELECT * FROM Users order   by createdAt DESC";
        QueryRequest query = QueryRequest.builder()
                .orderBy("name", SortDirection.ASC)
                .build();

        assertEquals(sql, appendResultSetControl(sql, query));
    }

    private static String appendResultSetControl(String sql, QueryRequest query) throws Exception {
        Method method = SpannerProviderClient.class.getDeclaredMethod(
                "appendResultSetControl", String.class, QueryRequest.class);
        method.setAccessible(true);
        return (String) method.invoke(null, sql, query);
    }
}
