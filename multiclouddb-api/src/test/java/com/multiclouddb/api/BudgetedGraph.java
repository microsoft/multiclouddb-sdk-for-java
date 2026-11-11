// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.
package com.multiclouddb.api;

import java.util.AbstractList;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/** Counts container/edge accesses and aborts fixtures before repeated DAG expansion. */
public final class BudgetedGraph {
    private final int budget;
    private int accesses;
    private int containers;
    private int edges;

    public BudgetedGraph(int budget) { this.budget = budget; }
    public int accesses() { return accesses; }
    public int containers() { return containers; }
    public int edges() { return edges; }

    private void access() {
        if (++accesses > budget) throw new AssertionError("Graph access budget exceeded: " + budget);
    }

    private <T> Iterator<T> counted(Iterator<T> source) {
        access();
        return new Iterator<>() {
            @Override public boolean hasNext() { access(); return source.hasNext(); }
            @Override public T next() { access(); edges++; return source.next(); }
        };
    }

    public Object list(Object... values) {
        return new AbstractList<Object>() {
            @Override public Object get(int index) { throw new AssertionError("Indexed graph access"); }
            @Override public int size() { return values.length; }
            @Override public Iterator<Object> iterator() {
                containers++;
                return counted(Arrays.asList(values).iterator());
            }
        };
    }

    public Object map(Object left, Object right) {
        Map<String, Object> values = Map.of("left", left, "right", right);
        return new AbstractMap<String, Object>() {
            @Override public Set<Entry<String, Object>> entrySet() {
                access();
                containers++;
                return new AbstractSet<>() {
                    @Override public int size() { return values.size(); }
                    @Override public Iterator<Entry<String, Object>> iterator() {
                        Iterator<Entry<String, Object>> source = counted(values.entrySet().iterator());
                        return new Iterator<>() {
                            @Override public boolean hasNext() { return source.hasNext(); }
                            @Override public Entry<String, Object> next() {
                                Entry<String, Object> entry = source.next();
                                return new SimpleImmutableEntry<>(entry) {
                                    @Override public String getKey() { access(); return super.getKey(); }
                                    @Override public Object getValue() { access(); return super.getValue(); }
                                };
                            }
                        };
                    }
                };
            }
        };
    }
}
