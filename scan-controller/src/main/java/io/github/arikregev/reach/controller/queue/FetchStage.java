package io.github.arikregev.reach.controller.queue;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.arikregev.reach.common.ReasonCode;
import io.github.arikregev.reach.common.ScanStatus;
import io.github.arikregev.reach.controller.config.ControllerConfig;
import io.github.arikregev.reach.controller.dt.DependencyTrack;
import io.github.arikregev.reach.controller.dt.DtException;
import io.github.arikregev.reach.controller.dt.DtFinding;
import io.github.arikregev.reach.controller.dt.RepoBinding;
import io.github.arikregev.reach.controller.github.GitHubApp;
import io.github.arikregev.reach.controller.github.GitHubException;
import io.github.arikregev.reach.controller.jobs.InputBundle;
import io.github.arikregev.reach.controller.jobs.JobLauncher;
import io.github.arikregev.reach.controller.s3.S3Storage;
import io.github.arikregev.reach.controller.store.ClaimedScan;
import io.github.arikregev.reach.controller.store.StoreUnavailableException;
import jakarta.enterprise.context.ApplicationScoped;
import java.net.URI;
import java.util.List;
import org.jboss.logging.Logger;

/**
 * The controller's part of FETCHING (architecture.md 4.1, 8.1): DT project lookup, uuid check, findings and their
 * hash, the SBOM, the input bundle in S3, a GitHub installation token, then the source-fetch Job. HTTP calls run
 * outside any transaction; only state changes are written in one. A scan with no findings goes straight to
 * PUBLISHING (4.2). With {@code reach.job.enabled=false} the stage ends before the Job (local runs).
 */
@ApplicationScoped
public class FetchStage {

    private static final Logger LOG = Logger.getLogger(FetchStage.class);

    private final DependencyTrack dt;
    private final Transitions transitions;
    private final InputBundle bundle;
    private final S3Storage s3;
    private final GitHubApp github;
    private final JobLauncher jobs;
    private final ControllerConfig config;

    public FetchStage(DependencyTrack dt, Transitions transitions, InputBundle bundle, S3Storage s3, GitHubApp github,
                      JobLauncher jobs, ControllerConfig config) {
        this.dt = dt;
        this.transitions = transitions;
        this.bundle = bundle;
        this.s3 = s3;
        this.github = github;
        this.jobs = jobs;
        this.config = config;
    }

    public void run(ClaimedScan scan) {
        try {
            DependencyTrack.Project project = dt.lookupProject(scan.dtProjectName(), scan.dtProjectVersion());
            if (scan.dtProjectUuid() != null && !scan.dtProjectUuid().equals(project.uuid())) {
                transitions.fetchFailed(scan, ReasonCode.DT_UUID_MISMATCH, "request dt_project.uuid "
                        + scan.dtProjectUuid() + " differs from the lookup result " + project.uuid());
                return;
            }
            if (!checkBinding(scan, project)) {
                return;
            }
            List<DtFinding> findings = dt.findings(project.uuid());
            String hash = DependencyTrack.findingsHash(findings);
            if (!transitions.findingsLoaded(scan, project.uuid(), hash, findings)) {
                return;
            }
            LOG.infof("Scan %s: loaded %d DT finding(s) of %s@%s", scan.id(), findings.size(),
                    scan.dtProjectName(), scan.dtProjectVersion());
            if (findings.isEmpty()) {
                transitions.advance(scan, ScanStatus.FETCHING, ScanStatus.PUBLISHING);
                return;
            }
            JsonNode sbom = dt.bom(project.uuid());
            s3.putJson(JobLauncher.keys(scan).inputBundle(), bundle.render(scan, project.uuid(), findings, sbom));
            String token = github.tokenFor(scan.org(), scan.repo(), scan.commitSha());
            if (config.job().enabled()) {
                jobs.launchFetch(scan, token);
            }
        } catch (DtException e) {
            fail(scan, e.reason(), e);
        } catch (GitHubException e) {
            fail(scan, e.reason(), e);
        } catch (S3Storage.S3UnavailableException e) {
            fail(scan, e.reason(), e);
        } catch (JobLauncher.JobLaunchException e) {
            fail(scan, e.reason(), e);
        } catch (StoreUnavailableException e) {
            // Postgres is down, so no state can be written; the sweep resumes the scan.
            throw e;
        } catch (RuntimeException e) {
            // Deterministic failures (a constraint violation, malformed data) count against max-attempts instead of
            // being retried by every sweep forever.
            LOG.errorf(e, "Scan %s: FETCHING failed unexpectedly", scan.id());
            transitions.fetchFailed(scan, ReasonCode.INTERNAL_ERROR, e.getClass().getSimpleName() + ": "
                    + e.getMessage());
        }
    }

    /**
     * D-037: the DT project must name the scan's repository, through a {@code vcs} external reference or a
     * {@code reach-repo=} tag, before the findings of one repository are judged against another's code. False when
     * the scan failed here.
     */
    private boolean checkBinding(ClaimedScan scan, DependencyTrack.Project project) {
        ControllerConfig.RepoBindingMode mode = config.dt().repoBinding();
        if (mode == ControllerConfig.RepoBindingMode.OFF) {
            return true;
        }
        String scanRepo = URI.create(scan.repoUrl()).getHost() + "/" + scan.org() + "/" + scan.repo();
        String dtProject = scan.dtProjectName() + "@" + scan.dtProjectVersion();
        switch (RepoBinding.check(project.repositories(), scanRepo)) {
            case BOUND -> {
                return true;
            }
            case MISMATCH -> {
                transitions.fetchFailed(scan, ReasonCode.DT_REPO_MISMATCH, "DT project " + dtProject
                        + " is bound to " + String.join(", ", project.repositories()) + ", not " + scanRepo);
                return false;
            }
            default -> {
                if (mode == ControllerConfig.RepoBindingMode.REQUIRED) {
                    transitions.fetchFailed(scan, ReasonCode.DT_REPO_UNBOUND, "DT project " + dtProject
                            + " names no repository; add a vcs external reference or the tag "
                            + RepoBinding.TAG_PREFIX + scanRepo);
                    return false;
                }
                if (transitions.writeBackCapped(scan, "DT project " + dtProject + " names no repository, so "
                        + "dt_write_back is lowered to COMMENT; add a vcs external reference or the tag "
                        + RepoBinding.TAG_PREFIX + scanRepo)) {
                    LOG.infof("Scan %s: DT project %s is unbound; write-back capped at COMMENT", scan.id(), dtProject);
                }
                return true;
            }
        }
    }

    private void fail(ClaimedScan scan, ReasonCode reason, RuntimeException e) {
        LOG.warnf("Scan %s: %s", scan.id(), e.getMessage());
        transitions.fetchFailed(scan, reason, e.getMessage());
    }
}
