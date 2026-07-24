package com.example.shared;

/**
 * Spike fixture — version 1 of a synthetic "private dependency" two
 * components will each pull a different, incompatible version of.
 *
 * format(String) is the method v2 removes in favour of an incompatible
 * signature. A component that expects this version but ends up loading
 * v2's class instead will fail with NoSuchMethodError, not silently
 * misbehave — deliberately, so isolation failures are loud.
 */
public class Formatter {

    public String format(String input) {
        return "[v1] " + input;
    }
}
