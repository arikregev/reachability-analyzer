package io.github.arikregev.reach.controller.jobs;

import io.github.arikregev.reach.common.ScanStatus;

/** The two Jobs of a scan (D-008): source-fetch in the trusted namespace, scan in the untrusted one. */
public enum Stage {
    FETCH("fetch", ScanStatus.FETCHING),
    SCAN("scan", ScanStatus.SCANNING);

    private final String label;
    private final ScanStatus runsIn;

    Stage(String label, ScanStatus runsIn) {
        this.label = label;
        this.runsIn = runsIn;
    }

    public String label() {
        return label;
    }

    /** The scan status while this Job runs. */
    public ScanStatus runsIn() {
        return runsIn;
    }

    public static Stage of(String label) {
        for (Stage s : values()) {
            if (s.label.equals(label)) {
                return s;
            }
        }
        throw new IllegalArgumentException("unknown stage " + label);
    }
}
