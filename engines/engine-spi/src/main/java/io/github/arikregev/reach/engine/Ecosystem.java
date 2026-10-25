package io.github.arikregev.reach.engine;

/** Package ecosystems, named after their purl type. */
public enum Ecosystem {
    MAVEN("maven"),
    PYPI("pypi"),
    GOLANG("golang"),
    NPM("npm");

    private final String purlType;

    Ecosystem(String purlType) {
        this.purlType = purlType;
    }

    public String purlType() {
        return purlType;
    }

    /** The ecosystem of a purl such as {@code pkg:maven/g/a@1}, or null for any other type. */
    public static Ecosystem ofPurl(String purl) {
        if (purl == null || !purl.startsWith("pkg:")) {
            return null;
        }
        int slash = purl.indexOf('/', 4);
        String type = slash < 0 ? "" : purl.substring(4, slash).toLowerCase(java.util.Locale.ROOT);
        for (Ecosystem e : values()) {
            if (e.purlType.equals(type)) {
                return e;
            }
        }
        return null;
    }
}
