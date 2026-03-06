package com.hyperscaledb.api;

/**
 * Provider-neutral error categories for consistent failure handling across
 * providers.
 */
public enum HyperscaleDbErrorCategory {
    INVALID_REQUEST,
    AUTHENTICATION_FAILED,
    AUTHORIZATION_FAILED,
    NOT_FOUND,
    CONFLICT,
    THROTTLED,
    TRANSIENT_FAILURE,
    PERMANENT_FAILURE,
    PROVIDER_ERROR,
    UNSUPPORTED_CAPABILITY
}
