// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.conformance;

import java.math.BigDecimal;
import java.util.Map;

/** Application-owned example: no SDK mapping interface or JSON library is needed. */
public final class PlainCustomerMapping {
    public record Customer(String name, int quantity) {}

    public Map<String, Object> toMap(Customer customer) {
        return Map.of("customer_name", customer.name(), "quantity", customer.quantity());
    }

    public Customer fromMap(Map<String, Object> document) {
        if (!(document.get("customer_name") instanceof String name)
                || !(document.get("quantity") instanceof Number quantity)) {
            throw new IllegalArgumentException("Invalid customer fields.");
        }
        return new Customer(name, new BigDecimal(quantity.toString()).intValueExact());
    }
}
