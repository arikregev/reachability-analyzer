package io.github.arikregev.reach.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Builds the status.v1 Scan Activity events (architecture.md 5.4a e). Key is the scanId; consumers dedupe on
 * (scan.uid, scan_status, attempt). Completed events (activity_id 2) are built with the results event in PR 5.
 */
public final class StatusEvents {

    public static final int CLASS_UID = 6007;
    public static final String OCSF_VERSION = "1.9.0";
    public static final String PRODUCT = "reachability-pod";

    /** The scan fields every status event carries. */
    public record Scan(UUID scanId, String requestUid, String dtProjectName, String dtProjectVersion, UUID dtProjectUuid) {
    }

    private final ObjectMapper mapper;

    public StatusEvents(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** QUEUED (first): activity 1 Started, with the intake warnings. */
    public ObjectNode queued(Scan scan, Instant time, List<?> warnings) {
        ObjectNode e = base(scan, time, 1, 99, ScanStatus.QUEUED.name());
        ObjectNode r = reach(e, scan, ScanStatus.QUEUED);
        r.put("attempt", 1);
        r.set("warnings", mapper.valueToTree(warnings));
        return e;
    }

    /** FETCHING, SCANNING, PUBLISHING: activity 99 Other. */
    public ObjectNode progress(Scan scan, Instant time, ScanStatus state, int attempt) {
        ObjectNode e = base(scan, time, 99, 99, state.name());
        reach(e, scan, state).put("attempt", attempt);
        return e;
    }

    /** QUEUED (retry): activity 99 Other with the retryable reason and {@code not_before}. */
    public ObjectNode retry(Scan scan, Instant time, int attempt, ReasonCode reason, String detail, Instant notBefore) {
        ObjectNode e = base(scan, time, 99, 99, ScanStatus.QUEUED.name());
        e.put("status_detail", detail);
        ObjectNode r = reach(e, scan, ScanStatus.QUEUED);
        r.put("attempt", attempt);
        r.put("reason", reason.name());
        r.put("retryable", true);
        r.put("not_before", notBefore.toString());
        return e;
    }

    /** FAILED: activity 6 Error. */
    public ObjectNode failed(Scan scan, Instant time, int attempt, ReasonCode reason, String detail) {
        ObjectNode e = base(scan, time, 6, 2, "Failure");
        e.put("status_detail", detail);
        ObjectNode r = reach(e, scan, ScanStatus.FAILED);
        r.put("attempt", attempt);
        r.put("reason", reason.name());
        r.put("retryable", false);
        return e;
    }

    /** CANCELLED: activity 3 Cancelled. */
    public ObjectNode cancelled(Scan scan, Instant time, int attempt) {
        ObjectNode e = base(scan, time, 3, 99, ScanStatus.CANCELLED.name());
        reach(e, scan, ScanStatus.CANCELLED).put("attempt", attempt);
        return e;
    }

    private ObjectNode base(Scan scan, Instant time, int activityId, int statusId, String status) {
        ObjectNode e = mapper.createObjectNode();
        e.put("class_uid", CLASS_UID);
        e.put("category_uid", 6);
        e.put("activity_id", activityId);
        e.put("type_uid", CLASS_UID * 100 + activityId);
        e.put("time", time.toEpochMilli());
        ObjectNode metadata = e.putObject("metadata");
        metadata.put("version", OCSF_VERSION);
        metadata.put("uid", scan.scanId().toString());
        if (scan.requestUid() != null) {
            metadata.put("correlation_uid", scan.requestUid());
        }
        metadata.putObject("product").put("name", PRODUCT);
        ObjectNode s = e.putObject("scan");
        s.put("type", "reachability");
        s.put("uid", scan.scanId().toString());
        e.put("status_id", statusId);
        e.put("status", status);
        return e;
    }

    private static ObjectNode reach(ObjectNode event, Scan scan, ScanStatus state) {
        ObjectNode r = event.putObject("unmapped").putObject("reachability");
        r.put("schema_version", "1");
        r.put("scan_status", state.name());
        ObjectNode dt = r.putObject("dt_project");
        dt.put("name", scan.dtProjectName());
        dt.put("version", scan.dtProjectVersion());
        dt.put("uuid", scan.dtProjectUuid() == null ? null : scan.dtProjectUuid().toString());
        return r;
    }
}
