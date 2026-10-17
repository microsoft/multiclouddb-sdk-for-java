// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.api.document;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.nio.ByteBuffer;
import java.lang.reflect.InvocationTargetException;

import static org.junit.jupiter.api.Assertions.*;

class DocumentModelTest {
    @Test
    void snapshotsEveryMutableContainer() {
        List<DocumentValue> list = new ArrayList<>(List.of(new StringValue("original")));
        ArrayValue array = ArrayValue.of(list);
        Map<String, DocumentValue> fields = new LinkedHashMap<>();
        fields.put("items", array);
        Document document = Document.of(ObjectValue.of(fields));
        list.clear();
        fields.clear();
        assertEquals(new StringValue("original"), array.values().get(0));
        assertSame(array, document.get("items").orElseThrow());
        assertEquals(1, array.values().size());
        assertThrows(UnsupportedOperationException.class, () -> array.values().clear());
        assertThrows(UnsupportedOperationException.class, () -> document.root().fields().clear());
    }

    @Test
    void distinguishesNullAbsenceAndRejectsDuplicatesWithoutOverwriting() {
        Document.Builder builder = Document.builder().put("present", NullValue.INSTANCE);
        Document first = builder.build();
        assertEquals(NullValue.INSTANCE, first.get("present").orElseThrow());
        assertTrue(first.get("absent").isEmpty());
        assertThrows(IllegalArgumentException.class,
                () -> builder.put("present", new StringValue("replacement")));
        builder.put("later", new BooleanValue(true));
        assertTrue(first.get("later").isEmpty());
        assertEquals(NullValue.INSTANCE, builder.build().get("present").orElseThrow());
        assertThrows(NullPointerException.class, () -> builder.put("invalid", null));
    }

    @Test
    void objectEqualityIgnoresOrderButArrayEqualityDoesNot() {
        Document first = Document.builder()
                .put("n", NumberValue.of(1)).put("null", NullValue.INSTANCE).build();
        Document second = Document.builder()
                .put("null", NullValue.INSTANCE).put("n", NumberValue.of(1.0)).build();
        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
        assertEquals(List.of("n", "null"), new ArrayList<>(first.root().fields().keySet()));
        assertNotEquals(ArrayValue.of(List.of(NumberValue.of(1), NumberValue.of(2))),
                ArrayValue.of(List.of(NumberValue.of(2), NumberValue.of(1))));
    }

    @Test
    void closedAlgebraContainsExactlyTheSixJsonLikeKinds() {
        assertTrue(DocumentValue.class.isSealed());
        assertEquals(Set.of(NullValue.class, BooleanValue.class, StringValue.class, NumberValue.class,
                ArrayValue.class, ObjectValue.class), Set.of(DocumentValue.class.getPermittedSubclasses()));
    }

    @Test
    void erasedContainerCallsCannotSmuggleBinaryOrArbitraryObjectsIntoTheModel() throws Exception {
        var objectFactory = ObjectValue.class.getMethod("of", Map.class);
        var arrayFactory = ArrayValue.class.getMethod("of", List.class);
        for (Object unsupported : List.of(new byte[]{1, 2}, ByteBuffer.wrap(new byte[]{1, 2}), new Object())) {
            InvocationTargetException objectFailure = assertThrows(InvocationTargetException.class,
                    () -> objectFactory.invoke(null, Map.of("value", unsupported)));
            assertInstanceOf(ClassCastException.class, objectFailure.getCause());
            InvocationTargetException arrayFailure = assertThrows(InvocationTargetException.class,
                    () -> arrayFactory.invoke(null, List.of(unsupported)));
            assertInstanceOf(ClassCastException.class, arrayFailure.getCause());
        }
    }

    @Test
    void checksDepthIncludingRootAndSharedSubtrees() {
        DocumentValue value = NullValue.INSTANCE;
        for (int i = 0; i < 127; i++) value = ArrayValue.of(List.of(value));
        DocumentValue at127 = value;
        assertDoesNotThrow(() -> Document.builder().put("items", at127).build());
        ArrayValue at128 = ArrayValue.of(List.of(at127, at127));
        assertThrows(IllegalArgumentException.class,
                () -> Document.builder().put("items", at128).build());
        assertThrows(IllegalArgumentException.class, () -> ArrayValue.of(List.of(at128)));
    }

    @Test
    void rejectsJavaNullChildrenAndNames() {
        List<DocumentValue> list = new ArrayList<>();
        list.add(null);
        assertThrows(NullPointerException.class, () -> ArrayValue.of(list));
        Map<String, DocumentValue> fields = new LinkedHashMap<>();
        fields.put("a", null);
        assertThrows(NullPointerException.class, () -> ObjectValue.of(fields));
        fields.clear();
        fields.put(null, NullValue.INSTANCE);
        assertThrows(NullPointerException.class, () -> ObjectValue.of(fields));
    }
}
