package io.itara.serializer.json;

import io.itara.spi.serializer.ItaraSerializerConfig;
import io.itara.spi.serializer.ItaraSerializerGroupingKey;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * The parsed config for the JSON serializer.
 *
 * There is no dedicated config surface yet — no pretty-printing toggle,
 * no custom Jackson module list, nothing beyond the raw params map itself
 * — but unlike the Java serializer this one is expected to grow real
 * per-connection knobs before long, so it is deliberately parsed fresh
 * per connection rather than handed out as a fixed singleton.
 *
 * Doubles as its own grouping key, based on params equality: two
 * connections with equal params (including two connections that both
 * declare none) share one JsonItaraSerializer instance; connections with
 * different params each get their own. When real, typed config fields
 * are added here, replace the raw map with them and base equals()/
 * hashCode() on those fields instead — the raw map is a placeholder for
 * "whatever this connection configured", not a considered design.
 */
final class JsonSerializerConfig implements ItaraSerializerConfig, ItaraSerializerGroupingKey {

    private final Map<String, String> params;

    JsonSerializerConfig(Map<String, String> params) {
        this.params = Collections.unmodifiableMap(params);
    }

    @Override
    public ItaraSerializerGroupingKey groupingKey() {
        return this;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof JsonSerializerConfig)) {
            return false;
        }
        return params.equals(((JsonSerializerConfig) other).params);
    }

    @Override
    public int hashCode() {
        return Objects.hash(params);
    }
}
