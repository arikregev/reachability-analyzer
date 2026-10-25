package io.github.arikregev.reach.contracts;

/**
 * DT write-back modes, declared in rank order OFF &lt; COMMENT &lt; VEX &lt; ANALYSIS (architecture.md 5.4a i, D-004).
 */
public enum WriteBackMode {
    OFF,
    COMMENT,
    VEX,
    ANALYSIS;

    /** The lower-ranked of the two modes. */
    public WriteBackMode narrowTo(WriteBackMode ceiling) {
        return compareTo(ceiling) <= 0 ? this : ceiling;
    }
}
