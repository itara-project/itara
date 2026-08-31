package dev.itara.util;

import java.time.Duration;

/**
 * Parses human-friendly duration strings used in the wiring config.
 *
 * <p>Supports the common suffixes users will naturally write:
 * <ul>
 * <li>{@code ms} — milliseconds (e.g. {@code "500ms"})</li>
 * <li>{@code s} — seconds (e.g. {@code "2s"})</li>
 * <li>{@code m} — minutes (e.g. {@code "5m"})</li>
 * <li>{@code h} — hours (e.g. {@code "1h"})</li>
 * </ul>
 *
 * <p>Falls back to {@link Duration#parse} for ISO-8601 strings (e.g. "PT2S")
 * so that both forms are accepted. Throws {@link IllegalArgumentException}
 * for anything that matches neither form.
 */
public class DurationParser {

    private DurationParser() {}

    /**
     * Parses a duration string in either suffix form (e.g. {@code "500ms"},
     * {@code "2s"}, {@code "5m"}, {@code "1h"}) or ISO-8601 form (e.g.
     * {@code "PT2S"}). Matching is case-insensitive and leading/trailing
     * whitespace is stripped.
     *
     * @param value the duration string to parse
     * @return the parsed duration
     * @throws IllegalArgumentException if {@code value} is null, blank, or
     *         matches neither the suffix nor the ISO-8601 form
     */
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
