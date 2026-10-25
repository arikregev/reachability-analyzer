package io.github.arikregev.reach.controller.queue;

import io.github.arikregev.reach.common.ReasonCode;
import io.github.arikregev.reach.controller.dt.DependencyTrack;
import io.github.arikregev.reach.controller.dt.DtException;
import io.github.arikregev.reach.controller.dt.DtFinding;
import io.github.arikregev.reach.controller.store.ClaimedScan;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import org.jboss.logging.Logger;

/**
 * The DT part of FETCHING (architecture.md 4.1, 8.1): project lookup by name and version, uuid check, findings and
 * their hash. HTTP calls run outside any transaction; only the result is written in one.
 */
@ApplicationScoped
public class FetchStage {

    private static final Logger LOG = Logger.getLogger(FetchStage.class);

    private final DependencyTrack dt;
    private final Transitions transitions;

    public FetchStage(DependencyTrack dt, Transitions transitions) {
        this.dt = dt;
        this.transitions = transitions;
    }

    public void run(ClaimedScan scan) {
        try {
            DependencyTrack.Project project = dt.lookupProject(scan.dtProjectName(), scan.dtProjectVersion());
            if (scan.dtProjectUuid() != null && !scan.dtProjectUuid().equals(project.uuid())) {
                transitions.fetchFailed(scan, ReasonCode.DT_UUID_MISMATCH, "request dt_project.uuid "
                        + scan.dtProjectUuid() + " differs from the lookup result " + project.uuid());
                return;
            }
            List<DtFinding> findings = dt.findings(project.uuid());
            String hash = DependencyTrack.findingsHash(findings);
            if (transitions.findingsLoaded(scan, project.uuid(), hash, findings)) {
                LOG.infof("Scan %s: loaded %d DT finding(s) of %s@%s", scan.id(), findings.size(),
                        scan.dtProjectName(), scan.dtProjectVersion());
            }
        } catch (DtException e) {
            LOG.warnf("Scan %s: %s", scan.id(), e.getMessage());
            transitions.fetchFailed(scan, e.reason(), e.getMessage());
        }
    }
}
