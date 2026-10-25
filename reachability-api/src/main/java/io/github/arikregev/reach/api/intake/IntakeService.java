package io.github.arikregev.reach.api.intake;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.arikregev.reach.api.config.ReachConfig;
import io.github.arikregev.reach.api.store.NewScan;
import io.github.arikregev.reach.api.store.PolicyStore;
import io.github.arikregev.reach.api.store.ScanRow;
import io.github.arikregev.reach.api.store.ScanStore;
import io.github.arikregev.reach.common.ScanStatus;
import io.github.arikregev.reach.common.StatusEvents;
import io.github.arikregev.reach.contracts.OutputFormat;
import io.github.arikregev.reach.contracts.RepoRef;
import io.github.arikregev.reach.contracts.ScanRequest;
import io.github.arikregev.reach.contracts.Violation;
import io.github.arikregev.reach.contracts.WriteBackMode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Turns a valid request into a QUEUED scan in one transaction: idempotency on {@code metadata.uid}, the 5.1 and
 * 5.4a rules (priority permission, write-back narrowing, link TTL cap), the queue row and the first status event in
 * the outbox (D-006). Shared by the REST and Kafka intakes.
 */
@ApplicationScoped
public class IntakeService {

    static final String DEFAULT_CLASS = "default";
    private static final String REACH = "/unmapped/reachability";

    private final ScanStore scans;
    private final PolicyStore policies;
    private final ReachConfig config;
    private final ObjectMapper mapper;
    private final StatusEvents events;

    public IntakeService(ScanStore scans, PolicyStore policies, ReachConfig config, ObjectMapper mapper) {
        this.scans = scans;
        this.policies = policies;
        this.config = config;
        this.mapper = mapper;
        this.events = new StatusEvents(mapper);
    }

    @Transactional
    public SubmitResult submit(ScanRequest request, Caller caller) {
        RepoRef repo = validateRules(request);

        if (request.requestUid() != null) {
            var existing = scans.findByRequestUid(request.requestUid());
            if (existing.isPresent()) {
                return duplicate(existing.get(), request);
            }
        }

        UUID scanId = UUID.randomUUID();
        Instant now = scans.now();
        if (request.requestUid() != null && !scans.insertRequestKey(request.requestUid(), scanId, now)) {
            // A concurrent intake with the same metadata.uid committed first.
            return duplicate(scans.findByRequestUid(request.requestUid()).orElseThrow(), request);
        }

        List<Warning> warnings = new ArrayList<>();
        String priorityClass = resolvePriority(request.priority(), caller, warnings);
        WriteBackMode writeBack = resolveWriteBack(request.dtWriteBack(), repo, warnings);
        Duration linkTtl = resolveLinkTtl(request.linkTtl(), warnings);
        List<OutputFormat> formats = request.formats() != null ? request.formats() : List.of(OutputFormat.values());

        ObjectNode options = mapper.createObjectNode();
        ArrayNode formatArray = options.putArray("formats");
        formats.forEach(f -> formatArray.add(f.wire()));
        options.put("link_ttl", linkTtl.toString());
        options.put("requested_priority", request.priority());
        options.put("requested_dt_write_back", request.dtWriteBack() == null ? null : request.dtWriteBack().name());
        options.set("warnings", mapper.valueToTree(warnings));

        scans.insertQueued(new NewScan(scanId, now, request.requestUid(), caller.source(), caller.principal(),
                repo.org(), repo.repo(), request.repoUrl(), request.commit(), request.dtProjectName(),
                request.dtProjectVersion(), request.dtProjectUuid(), priorityClass, writeBack,
                json(options), json(request.raw())));
        scans.insertOutbox(config.kafka().topics().status(), scanId.toString(),
                json(events.queued(new StatusEvents.Scan(scanId, request.requestUid(), request.dtProjectName(),
                        request.dtProjectVersion(), request.dtProjectUuid()), now, warnings)));

        return new SubmitResult(scanId, request.requestUid(), ScanStatus.QUEUED, false, priorityClass, writeBack,
                now, List.copyOf(warnings));
    }

    private RepoRef validateRules(ScanRequest request) {
        List<Violation> violations = new ArrayList<>();
        RepoRef repo = RepoRef.parse(request.repoUrl()).orElse(null);
        if (repo == null) {
            violations.add(new Violation(REACH + "/repo/url",
                    "must be https://<host>/<org>/<repo> without credentials, port, query or fragment"));
        } else if (config.github().allowedHosts().stream().noneMatch(h -> h.toLowerCase(Locale.ROOT).equals(repo.host()))) {
            violations.add(new Violation(REACH + "/repo/url", "host " + repo.host() + " is not an allowed GitHub host"));
        }
        if (request.linkTtl() != null && (request.linkTtl().isZero() || request.linkTtl().isNegative())) {
            violations.add(new Violation(REACH + "/link_ttl", "must be a positive duration"));
        }
        if (!violations.isEmpty()) {
            throw new RequestRejectedException(violations);
        }
        return repo;
    }

    /**
     * The class a request runs in. The fallback is {@code default}, or the lowest enabled class when {@code default}
     * is disabled, so a request is never queued under a class the claim query skips. Classes weighted above the
     * fallback need a role (REST) or the Kafka allow-list; this fails closed when {@code default} is missing.
     */
    private String resolvePriority(String requested, Caller caller, List<Warning> warnings) {
        Map<String, Integer> weights = policies.enabledPriorityWeights();
        String fallback = weights.containsKey(DEFAULT_CLASS) ? DEFAULT_CLASS : weights.entrySet().stream()
                .min(Map.Entry.<String, Integer>comparingByValue().thenComparing(Map.Entry.comparingByKey()))
                .map(Map.Entry::getKey)
                .orElseThrow(NoPriorityClassException::new);
        if (requested == null || requested.equals(DEFAULT_CLASS)) {
            if (!fallback.equals(DEFAULT_CLASS)) {
                warnings.add(new Warning(Warning.PRIORITY_DOWNGRADED,
                        "priority class default is disabled; using " + fallback));
            }
            return fallback;
        }
        Integer weight = weights.get(requested);
        if (weight == null) {
            warnings.add(new Warning(Warning.PRIORITY_DOWNGRADED,
                    "priority class " + requested + " is unknown or disabled; using " + fallback));
            return fallback;
        }
        if (weight <= weights.get(fallback)) {
            return requested;
        }
        boolean permitted = switch (caller.source()) {
            case REST -> caller.roles().contains("reach-priority-" + requested);
            case KAFKA -> config.auth().kafkaPriorityClasses().orElse(Set.of()).contains(requested);
        };
        if (!permitted) {
            warnings.add(new Warning(Warning.PRIORITY_DOWNGRADED, caller.source() == Caller.Source.REST
                    ? "priority class " + requested + " requires role reach-priority-" + requested + "; using " + fallback
                    : "priority class " + requested + " is not allowed for Kafka requests; using " + fallback));
            return fallback;
        }
        return requested;
    }

    /** Rule 5.4a i: the request may pick an equal or lower mode than the most specific policy. */
    private WriteBackMode resolveWriteBack(WriteBackMode requested, RepoRef repo, List<Warning> warnings) {
        WriteBackMode policy = policies.writeBackPolicy(repo.org(), repo.fullName())
                .orElse(config.dt().writeback().mode());
        if (requested == null) {
            return policy;
        }
        WriteBackMode effective = requested.narrowTo(policy);
        if (effective != requested) {
            warnings.add(new Warning(Warning.WRITE_BACK_NARROWED,
                    "dt_write_back " + requested + " lowered to the policy mode " + policy));
        }
        return effective;
    }

    /** Rule 5.4a c: default reach.s3.link-ttl, capped at reach.s3.link-ttl-max. */
    private Duration resolveLinkTtl(Duration requested, List<Warning> warnings) {
        Duration ttl = requested != null ? requested : config.s3().linkTtl();
        Duration max = config.s3().linkTtlMax();
        if (ttl.compareTo(max) > 0) {
            warnings.add(new Warning(Warning.LINK_TTL_CAPPED, "link_ttl " + ttl + " capped at " + max));
            return max;
        }
        return ttl;
    }

    /**
     * The answer for a repeated {@code metadata.uid}: the first scan, with its stored warnings. When the new request
     * differs from the stored one, a {@code REQUEST_MISMATCH} warning says that the new content was ignored (D-039);
     * it is only returned, so the stored scan keeps its own warnings.
     */
    private SubmitResult duplicate(ScanRow row, ScanRequest request) {
        List<Warning> warnings = new ArrayList<>();
        JsonNode stored = row.options().path("warnings");
        stored.forEach(w -> warnings.add(new Warning(w.path("code").asText(), w.path("detail").asText())));
        JsonNode incoming = request.raw().at(REACH);
        boolean same = scans.storedReachability(row.id(), row.createdAt()).map(incoming::equals).orElse(false);
        if (!same) {
            warnings.add(new Warning(Warning.REQUEST_MISMATCH, "metadata.uid " + row.requestUid()
                    + " was already used by a different request; this request was ignored and the first scan is returned"));
        }
        return new SubmitResult(row.id(), row.requestUid(), row.status(), true, row.priorityClass(),
                row.dtWriteBack(), row.createdAt(), List.copyOf(warnings));
    }

    private String json(JsonNode node) {
        try {
            return mapper.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
