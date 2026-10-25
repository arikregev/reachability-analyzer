package io.github.arikregev.reach.api.intake;

/** A non-fatal adjustment made at intake (5.4a b). */
public record Warning(String code, String detail) {

    public static final String PRIORITY_DOWNGRADED = "PRIORITY_DOWNGRADED";
    public static final String WRITE_BACK_NARROWED = "WRITE_BACK_NARROWED";
    public static final String LINK_TTL_CAPPED = "LINK_TTL_CAPPED";
    /** A duplicate {@code metadata.uid} whose request differs from the stored one; returned, never stored (D-039). */
    public static final String REQUEST_MISMATCH = "REQUEST_MISMATCH";
}
