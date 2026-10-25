package io.github.arikregev.reach.engine;

/**
 * Finding verdicts (D-015). Declared in the order used to merge verdicts across modules: the strongest wins
 * (REACHABLE > POTENTIALLY_REACHABLE > UNKNOWN > NOT_REACHABLE, architecture.md 8.3).
 */
public enum Verdict {
    NOT_REACHABLE,
    UNKNOWN,
    POTENTIALLY_REACHABLE,
    REACHABLE;

    public Verdict strongest(Verdict other) {
        return other.ordinal() > ordinal() ? other : this;
    }
}
