package dev.itara.agent.config;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Discriminator for node types in the wiring configuration.
 *
 * <p>Used as the `kind` field on node declarations. When absent,
 * COMPONENT is assumed for backwards compatibility.
 */
public enum NodeKind {

    /** A deployable component with an activator and a contract interface. */
    @JsonProperty("component")
    COMPONENT,

    /** A named communication channel with no component implementation. */
    @JsonProperty("virtual")
    VIRTUAL
}
