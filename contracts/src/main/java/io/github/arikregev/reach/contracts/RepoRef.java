package io.github.arikregev.reach.contracts;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Optional;

/**
 * A GitHub repository named by an https URL of the form {@code https://<host>/<org>/<repo>[.git]}.
 * URLs with credentials, a port, a query, a fragment or extra path segments are rejected. Host, org and repo are
 * lower-cased, because GitHub names are case-insensitive; policies, caps, filters and dedupe compare these forms.
 */
public record RepoRef(String host, String org, String repo) {

    public static Optional<RepoRef> parse(String url) {
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            return Optional.empty();
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getRawUserInfo() != null || uri.getPort() != -1
                || uri.getRawQuery() != null || uri.getRawFragment() != null || uri.getPath() == null) {
            return Optional.empty();
        }
        String path = uri.getPath();
        if (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        if (path.endsWith(".git")) {
            path = path.substring(0, path.length() - 4);
        }
        String[] parts = path.split("/", -1);
        if (parts.length != 3 || !parts[0].isEmpty() || !validSegment(parts[1]) || !validSegment(parts[2])) {
            return Optional.empty();
        }
        return Optional.of(new RepoRef(uri.getHost().toLowerCase(Locale.ROOT),
                parts[1].toLowerCase(Locale.ROOT), parts[2].toLowerCase(Locale.ROOT)));
    }

    private static boolean validSegment(String s) {
        return !s.isEmpty() && !s.equals(".") && !s.equals("..") && s.matches("[A-Za-z0-9._-]+");
    }

    /** {@code <org>/<repo>}, the form used by the list filter and REPO-scoped policies. */
    public String fullName() {
        return org + "/" + repo;
    }
}
