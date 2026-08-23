package io.itara.spi.identity;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * The shared type a transport uses to surface a connection-level
 * credential it terminated itself — a TLS peer certificate, for example
 * (spec §7.4, §15.6) — to an authentication implementation, without either
 * side needing to know the other's concrete type.
 *
 * {@code mechanism} lets an authentication implementation cheaply check
 * "do I understand this" before touching {@code attributes} — e.g.
 * "mtls-peer-certificate". {@code attributes} carries the raw material;
 * what goes in there and under what keys is a convention between whatever
 * transport produces a given mechanism and whatever authentication
 * implementation is written to consume it — the same open-map idiom as
 * {@link ItaraIdentity#getClaims()}.
 */
public final class ItaraTransportCredential {

    private final String mechanism;
    private final Map<String, Object> attributes;

    private ItaraTransportCredential(Builder builder) {
        this.mechanism  = builder.mechanism;
        this.attributes = Collections.unmodifiableMap(new HashMap<>(builder.attributes));
    }

    public String getMechanism() { return mechanism; }
    public Map<String, Object> getAttributes() { return attributes; }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String mechanism;
        private Map<String, Object> attributes = Collections.emptyMap();

        public Builder mechanism(String mechanism) { this.mechanism = mechanism; return this; }
        public Builder attributes(Map<String, Object> attributes) {
            this.attributes = (attributes != null) ? attributes : Collections.emptyMap();
            return this;
        }

        public ItaraTransportCredential build() {
            if (mechanism == null || mechanism.isEmpty()) {
                throw new IllegalStateException("[Itara] ItaraTransportCredential requires a non-empty mechanism");
            }
            return new ItaraTransportCredential(this);
        }
    }

    @Override
    public String toString() {
        return "ItaraTransportCredential{mechanism='" + mechanism + "', attributeKeys=" + attributes.keySet() + "}";
    }
}
