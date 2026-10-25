package io.github.arikregev.reach.engine;

/** One frame of a call path: the method, and the line of the call it makes to the next frame (0 if unknown). */
public record Frame(String symbol, int line) {
}
