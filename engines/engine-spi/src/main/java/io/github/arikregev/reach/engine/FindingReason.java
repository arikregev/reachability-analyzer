package io.github.arikregev.reach.engine;

/** Finding-level reason codes (architecture.md 5.6). Separate from the scan-level codes of 5.4a f. */
public enum FindingReason {
    /** A vulnerable symbol from the knowledge base was reached. */
    SYMBOL_REACHED,
    /** Symbol data exists, the analysis completed and no vulnerable symbol was reached. */
    SYMBOL_NOT_REACHED,
    /** The knowledge base has no symbols for the vulnerability; the verdict is package-level. */
    NO_SYMBOL_DATA,
    /** The component's code is not referenced by anything reachable. */
    COMPONENT_NOT_REFERENCED,
    /** The component is in no module's resolved dependencies (8.3). */
    COMPONENT_NOT_RESOLVED,
    BUILD_FAILED,
    ENGINE_TIMEOUT,
    ENGINE_FAILED,
    UNSUPPORTED_LANGUAGE
}
