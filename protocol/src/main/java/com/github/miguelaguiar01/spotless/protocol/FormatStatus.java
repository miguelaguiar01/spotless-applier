package com.github.miguelaguiar01.spotless.protocol;

public enum FormatStatus {
    /** Input already satisfies the formatter; nothing to change. */
    CLEAN,
    /** Formatted text is in {@code text}. */
    FORMATTED,
    /** Formatter is not idempotent on this input (see Spotless PADDEDCELL.md). Nothing applied. */
    DID_NOT_CONVERGE,
    /** No format's include/exclude patterns match this path. */
    NOT_COVERED,
    /** Formatting failed; see {@code message}. */
    ERROR
}
