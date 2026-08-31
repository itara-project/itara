package dev.itara.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("DurationParser")
public class DurationParserTest {

    @Nested
    @DisplayName("human-friendly suffixes")
    class HumanFriendly {

        @Test
        @DisplayName("parses milliseconds")
        void parsesMilliseconds() {
            assertEquals(Duration.ofMillis(500), DurationParser.parse("500ms"));
        }

        @Test
        @DisplayName("parses seconds")
        void parsesSeconds() {
            assertEquals(Duration.ofSeconds(2), DurationParser.parse("2s"));
        }

        @Test
        @DisplayName("parses minutes")
        void parsesMinutes() {
            assertEquals(Duration.ofMinutes(5), DurationParser.parse("5m"));
        }

        @Test
        @DisplayName("parses hours")
        void parsesHours() {
            assertEquals(Duration.ofHours(1), DurationParser.parse("1h"));
        }

        @Test
        @DisplayName("strips surrounding whitespace")
        void stripsWhitespace() {
            assertEquals(Duration.ofSeconds(2), DurationParser.parse("  2s  "));
        }

        @Test
        @DisplayName("is case-insensitive")
        void isCaseInsensitive() {
            assertEquals(Duration.ofMillis(500), DurationParser.parse("500MS"));
            assertEquals(Duration.ofSeconds(2),  DurationParser.parse("2S"));
        }
    }

    @Nested
    @DisplayName("ISO-8601 fallback")
    class Iso8601 {

        @Test
        @DisplayName("parses ISO-8601 seconds")
        void parsesIso8601Seconds() {
            assertEquals(Duration.ofSeconds(2), DurationParser.parse("PT2S"));
        }

        @Test
        @DisplayName("parses ISO-8601 milliseconds")
        void parsesIso8601Millis() {
            assertEquals(Duration.ofMillis(500), DurationParser.parse("PT0.5S"));
        }

        @Test
        @DisplayName("parses ISO-8601 minutes")
        void parsesIso8601Minutes() {
            assertEquals(Duration.ofMinutes(5), DurationParser.parse("PT5M"));
        }
    }

    @Nested
    @DisplayName("invalid input")
    class InvalidInput {

        @Test
        @DisplayName("throws on blank string")
        void throwsOnBlank() {
            assertThrows(IllegalArgumentException.class, () -> DurationParser.parse(""));
            assertThrows(IllegalArgumentException.class, () -> DurationParser.parse("   "));
        }

        @Test
        @DisplayName("throws on null")
        void throwsOnNull() {
            assertThrows(IllegalArgumentException.class, () -> DurationParser.parse(null));
        }

        @Test
        @DisplayName("throws on non-numeric suffix")
        void throwsOnNonNumeric() {
            assertThrows(IllegalArgumentException.class, () -> DurationParser.parse("foos"));
        }

        @Test
        @DisplayName("throws on unrecognised format")
        void throwsOnUnrecognisedFormat() {
            assertThrows(IllegalArgumentException.class, () -> DurationParser.parse("2 seconds"));
        }
    }
}
