package io.itara.agent.config;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Discriminator for node types in the wiring configuration.
 *
 * Used as the `kind` field on node declarations. When absent,
 * COMPONENT is assumed for backwards compatibility.
 */
public enum NodeKind {

    @JsonProperty("component")
    COMPONENT,

    @JsonProperty("virtual")
    VIRTUAL
}
