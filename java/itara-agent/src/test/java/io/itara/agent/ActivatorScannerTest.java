package io.itara.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ActivatorScanner")
class ActivatorScannerTest {

    @Nested
    @DisplayName("readActivatorClassName")
    class ReadActivatorClassName {

        @Test
        @DisplayName("reads a single-line FQCN descriptor")
        void readsSingleLineDescriptor(@TempDir Path dir) throws IOException {
            Path file = dir.resolve("activator");
            Files.writeString(file, "com.example.pricing.PricingActivator\n");

            String result = ActivatorScanner.readActivatorClassName(file.toUri().toURL());

            assertEquals("com.example.pricing.PricingActivator", result);
        }

        @Test
        @DisplayName("skips leading blank lines and comments")
        void skipsBlankLinesAndComments(@TempDir Path dir) throws IOException {
            Path file = dir.resolve("activator");
            Files.writeString(file, """

                    # this file declares the activator class
                    com.example.pricing.PricingActivator
                    """);

            String result = ActivatorScanner.readActivatorClassName(file.toUri().toURL());

            assertEquals("com.example.pricing.PricingActivator", result);
        }

        @Test
        @DisplayName("strips surrounding whitespace")
        void stripsWhitespace(@TempDir Path dir) throws IOException {
            Path file = dir.resolve("activator");
            Files.writeString(file, "   com.example.pricing.PricingActivator   \n");

            String result = ActivatorScanner.readActivatorClassName(file.toUri().toURL());

            assertEquals("com.example.pricing.PricingActivator", result);
        }

        @Test
        @DisplayName("throws on an empty descriptor")
        void throwsOnEmptyDescriptor(@TempDir Path dir) throws IOException {
            Path file = dir.resolve("activator");
            Files.writeString(file, "");

            assertThrows(IllegalStateException.class,
                    () -> ActivatorScanner.readActivatorClassName(file.toUri().toURL()));
        }

        @Test
        @DisplayName("throws on a descriptor containing only comments")
        void throwsOnCommentsOnlyDescriptor(@TempDir Path dir) throws IOException {
            Path file = dir.resolve("activator");
            Files.writeString(file, "# nothing but comments\n\n");

            assertThrows(IllegalStateException.class,
                    () -> ActivatorScanner.readActivatorClassName(file.toUri().toURL()));
        }
    }
}
