package io.github.arikregev.reach.engine;

/**
 * A vulnerable symbol from the knowledge base (architecture.md 6.3). For Java, {@code symbol} is a class FQN
 * ({@code CLASS}), or a class FQN and method name such as {@code com.acme.Parser.parse} ({@code METHOD}); a method
 * symbol matches every overload.
 */
public record VulnerableSymbol(String symbol, Kind kind) {

    public enum Kind {
        FUNCTION,
        METHOD,
        CLASS,
        PACKAGE
    }
}
