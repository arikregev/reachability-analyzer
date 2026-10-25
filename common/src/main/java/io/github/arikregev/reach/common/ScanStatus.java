package io.github.arikregev.reach.common;

/** Scan states (architecture.md 4.2). */
public enum ScanStatus {
    QUEUED,
    FETCHING,
    SCANNING,
    PUBLISHING,
    SUCCEEDED,
    PARTIAL,
    FAILED,
    CANCELLED
}
