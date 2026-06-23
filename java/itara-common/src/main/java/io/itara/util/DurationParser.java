package io.itara.util;

import java.time.Duration;

/**
 * Parses human-friendly duration strings used in the wiring config.
 *
 * Supports the common suffixes users will naturally write:
 *   ms  — milliseconds  (e.g. "500ms")
 *   s   — seconds       (e.g. "2s")
 *   m   — minutes       (e.g. "5m")
 *   h   — hours         (e.g. "1h")
 *
 * Falls back to {@link Duration#parse} for ISO-8601 strings (e.g. "PT2S")
 * so that both forms are accepted. Throws RuntimeException
 * for anything that matches neither form.
 */
public class DurationParser {

    private DurationParser() {}

    public static Duration parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Duration value must not be blank");
        }
        String trimmed = value.strip().toLowerCase();

        // ISO-8601 strings always start with P — check first to avoid
        // suffix matching ambiguity (e.g. PT2S ends with 's')
        if (trimmed.startsWith("p")) {
            try {
                return Duration.parse(trimmed.toUpperCase());
            } catch (Exception e) {
                throw new IllegalArgumentException(
                        "Invalid duration value '" + value
                                + "'. Use a suffix (ms, s, m, h) or ISO-8601 (e.g. PT2S)");
            }
        }

        try {
            if (trimmed.endsWith("ms")) {
                return Duration.ofMillis(Long.parseLong(trimmed.substring(0, trimmed.length() - 2).strip()));
            }
            if (trimmed.endsWith("s")) {
                return Duration.ofSeconds(Long.parseLong(trimmed.substring(0, trimmed.length() - 1).strip()));
            }
            if (trimmed.endsWith("m")) {
                return Duration.ofMinutes(Long.parseLong(trimmed.substring(0, trimmed.length() - 1).strip()));
            }
            if (trimmed.endsWith("h")) {
                return Duration.ofHours(Long.parseLong(trimmed.substring(0, trimmed.length() - 1).strip()));
            }
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "Invalid duration value '" + value + "': " + e.getMessage());
        }

        throw new IllegalArgumentException(
                "Invalid duration value '" + value
                        + "'. Use a suffix (ms, s, m, h) or ISO-8601 (e.g. PT2S)");
    }
}
