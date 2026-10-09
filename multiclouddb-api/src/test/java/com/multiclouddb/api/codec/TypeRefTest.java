// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.api.codec;

import org.junit.jupiter.api.Test;

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.WildcardType;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TypeRefTest {
    @Test
    void capturesNestedGenericArgumentsWithoutJackson() {
        TypeRef<Map<String, List<Integer>>> ref = new TypeRef<>() {};
        ParameterizedType map = (ParameterizedType) ref.type();
        assertEquals(Map.class, map.getRawType());
        assertEquals(String.class, map.getActualTypeArguments()[0]);
        ParameterizedType list = (ParameterizedType) map.getActualTypeArguments()[1];
        assertEquals(Integer.class, list.getActualTypeArguments()[0]);
    }

    @Test
    void comparesTypeInformationAcrossClassAndCapturePaths() {
        assertEquals(TypeRef.of(String.class), new TypeRef<String>() {});
        assertEquals(new TypeRef<List<String>>() {}, new TypeRef<List<String>>() {});
        assertEquals(new TypeRef<List<String>>() {}.hashCode(), new TypeRef<List<String>>() {}.hashCode());
        assertNotEquals(new TypeRef<List<String>>() {}, new TypeRef<List<Integer>>() {});
    }

    @Test
    void preservesArraysWildcardsAndRawClassesWithoutClaimingCodecSupport() {
        assertEquals(String[].class, TypeRef.of(String[].class).type());
        assertInstanceOf(GenericArrayType.class, new TypeRef<List<String>[]>() {}.type());
        ParameterizedType wildcardList = (ParameterizedType) new TypeRef<List<? extends Number>>() {}.type();
        WildcardType wildcard = (WildcardType) wildcardList.getActualTypeArguments()[0];
        assertArrayEquals(new Object[]{Number.class}, wildcard.getUpperBounds());
        assertEquals(List.class, TypeRef.of(List.class).type());
    }

    @Test
    void rejectsUnresolvedVariablesAtCaptureRatherThanErasingThem() {
        assertThrows(IllegalArgumentException.class, TypeRefTest::unresolved);
        assertThrows(IllegalArgumentException.class, TypeRefTest::unresolvedArray);
        assertThrows(IllegalArgumentException.class, TypeRefTest::unresolvedWildcard);
    }

    private static <T> TypeRef<List<T>> unresolved() {
        return new TypeRef<>() {};
    }

    private static <T> TypeRef<T[]> unresolvedArray() {
        return new TypeRef<>() {};
    }

    private static <T> TypeRef<List<? extends T>> unresolvedWildcard() {
        return new TypeRef<>() {};
    }

    @Test
    @SuppressWarnings("rawtypes")
    void rejectsRawCaptureAndNullClass() {
        assertThrows(IllegalArgumentException.class, () -> new TypeRef() {});
        assertThrows(NullPointerException.class, () -> TypeRef.of(null));
    }

    @Test
    void namedDirectCaptureWorksButIndirectCaptureIsExplicitlyRejected() {
        assertEquals(String.class, new StringRef().type());
        assertThrows(IllegalArgumentException.class, IndirectRef::new);
    }

    private static class StringRef extends TypeRef<String> {}
    private static final class IndirectRef extends StringRef {}
}
