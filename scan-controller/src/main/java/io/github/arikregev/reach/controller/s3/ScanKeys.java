package io.github.arikregev.reach.controller.s3;

import java.util.UUID;

/**
 * Object keys of one scan attempt. Published results live under {@code <org>/<repo>/<commit>/<scanId>/} (D-016)
 * and are written only by the controller. Work files the Jobs exchange live under
 * {@code work/<scanId>/<attempt>/}, so a Job of an earlier attempt can never feed a newer one, the untrusted scan
 * Job never writes into the results prefix, and a short lifecycle rule can expire them (E-9).
 */
public record ScanKeys(String org, String repo, String commit, UUID scanId, int attempt) {

    public String resultPrefix() {
        return org + "/" + repo + "/" + commit + "/" + scanId + "/";
    }

    public String workPrefix() {
        return "work/" + scanId + "/" + attempt + "/";
    }

    public String sourceTarball() {
        return workPrefix() + "source.tar.zst";
    }

    public String inputBundle() {
        return workPrefix() + "input.json";
    }

    /** Written by the scan Job; validated and copied to the results prefix by the controller (outputs PR). */
    public String summary() {
        return workPrefix() + "summary.json";
    }
}
