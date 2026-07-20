package com.example.shared;

/**
 * Spike fixture — version 2 of the synthetic "private dependency".
 * Same FQCN as v1, but format(String) is gone, replaced by an
 * incompatible signature — a genuine binary break, not just a
 * behavior change.
 */
public class Formatter {

    public String format(String input, boolean uppercase) {
        String formatted = "[v2] " + input;
        return uppercase ? formatted.toUpperCase() : formatted;
    }
}
