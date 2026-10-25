package io.github.arikregev.reach.contracts;

import java.util.Arrays;

/** Output files a request may ask for (architecture.md 5.1, D-016). summary.json is always written. */
public enum OutputFormat {
    OCSF("ocsf"),
    CYCLONEDX_VEX("cyclonedx-vex"),
    OPENVEX("openvex");

    private final String wire;

    OutputFormat(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    public static OutputFormat fromWire(String value) {
        return Arrays.stream(values())
                .filter(f -> f.wire.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown format " + value));
    }
}
