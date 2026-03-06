package com.hyperscaledb.api;

import java.util.Objects;

/**
 * A structured warning indicating non-portable behavior or provider divergence.
 * Emitted when opt-in provider extensions are active or behavior differs across
 * providers.
 */
public final class PortabilityWarning {

    private final String code;
    private final String message;
    private final String scope;
    private final ProviderId provider;

    public PortabilityWarning(String code, String message, String scope, ProviderId provider) {
        this.code = Objects.requireNonNull(code, "code");
        this.message = Objects.requireNonNull(message, "message");
        this.scope = Objects.requireNonNull(scope, "scope");
        this.provider = Objects.requireNonNull(provider, "provider");
    }

    public String code() {
        return code;
    }

    public String message() {
        return message;
    }

    public String scope() {
        return scope;
    }

    public ProviderId provider() {
        return provider;
    }

    @Override
    public String toString() {
        return "PortabilityWarning{code=" + code + ", message=" + message
                + ", scope=" + scope + ", provider=" + provider.id() + "}";
    }
}
