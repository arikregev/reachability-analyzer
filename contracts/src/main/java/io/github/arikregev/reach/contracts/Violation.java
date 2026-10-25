package io.github.arikregev.reach.contracts;

/** One schema or rule violation, located by JSON Pointer (RFC 6901) into the request. */
public record Violation(String pointer, String message) {

    @Override
    public String toString() {
        return pointer + ": " + message;
    }
}
