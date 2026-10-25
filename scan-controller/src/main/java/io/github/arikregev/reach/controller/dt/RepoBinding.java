package io.github.arikregev.reach.controller.dt;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which repositories a DT project says it is built from (D-037): its {@code vcs} external references, as SBOM tools
 * such as cyclonedx-maven fill them from the SCM metadata, and tags of the form {@code reach-repo=<host>/<org>/<repo>}.
 * Every form is reduced to lower-case {@code <host>/<org>/<repo>}.
 */
public final class RepoBinding {

    public static final String TAG_PREFIX = "reach-repo=";

    /** {@code git@host:org/repo(.git)}, the scp-like form git accepts. */
    private static final Pattern SCP_LIKE = Pattern.compile("^[A-Za-z0-9._-]+@([A-Za-z0-9.-]+):(.+)$");

    /** The outcome of comparing a scan's repository with a DT project. */
    public enum Result {
        /** The project names the scan's repository. */
        BOUND,
        /** The project names repositories, none of them the scan's. */
        MISMATCH,
        /** The project names no repository. */
        UNBOUND
    }

    private RepoBinding() {
    }

    /** The repositories named by a DT project JSON object (from {@code /api/v1/project/lookup}). */
    public static Set<String> repositories(JsonNode project) {
        Set<String> repos = new LinkedHashSet<>();
        if (project == null) {
            return repos;
        }
        for (JsonNode ref : project.path("externalReferences")) {
            if ("vcs".equalsIgnoreCase(ref.path("type").asText())) {
                normalize(ref.path("url").asText()).ifPresent(repos::add);
            }
        }
        for (JsonNode tag : project.path("tags")) {
            String name = tag.isTextual() ? tag.asText() : tag.path("name").asText();
            if (name.toLowerCase(Locale.ROOT).startsWith(TAG_PREFIX)) {
                normalize("https://" + name.substring(TAG_PREFIX.length())).ifPresent(repos::add);
            }
        }
        return repos;
    }

    /** Compares {@code <host>/<org>/<repo>} of the scan with what the project names. */
    public static Result check(Set<String> projectRepos, String scanRepo) {
        if (projectRepos.isEmpty()) {
            return Result.UNBOUND;
        }
        return projectRepos.contains(scanRepo.toLowerCase(Locale.ROOT)) ? Result.BOUND : Result.MISMATCH;
    }

    /**
     * Reduces a VCS URL to lower-case {@code <host>/<org>/<repo>}. Accepts https, http, ssh and git URLs, the
     * scp-like {@code git@host:org/repo}, Maven's {@code scm:git:} prefix and npm's {@code git+} prefix; a trailing
     * {@code .git} and any path below the repository (e.g. {@code /tree/main}) are dropped.
     */
    public static Optional<String> normalize(String url) {
        if (url == null || url.isBlank()) {
            return Optional.empty();
        }
        String u = url.strip();
        if (u.regionMatches(true, 0, "scm:git:", 0, 8)) {
            u = u.substring(8);
        }
        if (u.regionMatches(true, 0, "git+", 0, 4)) {
            u = u.substring(4);
        }
        String host;
        String path;
        Matcher scp = SCP_LIKE.matcher(u);
        if (!u.contains("://") && scp.matches()) {
            host = scp.group(1);
            path = scp.group(2);
        } else {
            try {
                URI uri = new URI(u);
                host = uri.getHost();
                path = uri.getPath();
            } catch (URISyntaxException e) {
                return Optional.empty();
            }
        }
        if (host == null || path == null) {
            return Optional.empty();
        }
        String[] parts = path.replaceFirst("^/+", "").split("/");
        if (parts.length < 2 || parts[0].isEmpty() || parts[1].isEmpty()) {
            return Optional.empty();
        }
        String repo = parts[1].endsWith(".git") ? parts[1].substring(0, parts[1].length() - 4) : parts[1];
        if (repo.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of((host + "/" + parts[0] + "/" + repo).toLowerCase(Locale.ROOT));
    }
}
