package io.github.arikregev.reach.engine;

/** How precise a verdict is (architecture.md 7.3); a coarse verdict is always labelled. */
public enum Level {
    SYMBOL,
    CLASS_OR_PACKAGE,
    IMPORT,
    NONE
}
