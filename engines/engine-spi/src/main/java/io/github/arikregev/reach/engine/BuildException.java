package io.github.arikregev.reach.engine;

/** The module did not build; its findings become UNKNOWN / BUILD_FAILED. The message goes into the summary. */
public class BuildException extends Exception {

    public BuildException(String message) {
        super(message);
    }

    public BuildException(String message, Throwable cause) {
        super(message, cause);
    }
}
