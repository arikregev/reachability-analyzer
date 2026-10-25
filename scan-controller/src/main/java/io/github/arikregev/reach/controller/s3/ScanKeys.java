package io.github.arikregev.reach.controller.s3;

import java.util.UUID;

/**
 * Object keys of one scan. Results live under {@code <org>/<repo>/<commit>/<scanId>/} (D-016); work files the
 * Jobs exchange live under {@code work/<scanId>/} so a short lifecycle rule can expire them (E-9).
 */
public record ScanKeys(String org, String repo, String commit, UUID scanId) {

    public String resultPrefix() {
        return org + "/" + repo + "/" + commit + "/" + scanId + "/";
    }

    public String summary() {
        return resultPrefix() + "summary.json";
    }

    public String workPrefix() {
        return "work/" + scanId + "/";
    }

    public String sourceTarball() {
        return workPrefix() + "source.tar.zst";
    }

    public String inputBundle() {
        return workPrefix() + "input.json";
    }
}
