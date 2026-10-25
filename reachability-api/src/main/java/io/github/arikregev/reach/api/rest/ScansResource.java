package io.github.arikregev.reach.api.rest;

import io.github.arikregev.reach.api.config.ReachConfig;
import io.github.arikregev.reach.api.intake.Caller;
import io.github.arikregev.reach.api.intake.IntakeMetrics;
import io.github.arikregev.reach.api.intake.IntakeService;
import io.github.arikregev.reach.api.intake.RequestRejectedException;
import io.github.arikregev.reach.common.ScanStatus;
import io.github.arikregev.reach.api.intake.SubmitResult;
import io.github.arikregev.reach.api.intake.Warning;
import io.github.arikregev.reach.api.store.ScanListFilter;
import io.github.arikregev.reach.api.store.ScanRow;
import io.github.arikregev.reach.api.store.ScanStore;
import io.github.arikregev.reach.contracts.ParseResult;
import io.github.arikregev.reach.contracts.ScanRequestParser;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.StreamSupport;

/** Scan intake, status and list (architecture.md 5.3, 5.4a b). */
@Path("/api/v1/scans")
@Produces(MediaType.APPLICATION_JSON)
public class ScansResource {

    private final ScanRequestParser parser;
    private final IntakeService intake;
    private final ScanStore scans;
    private final ReachConfig config;
    private final SecurityIdentity identity;
    private final IntakeMetrics metrics;

    public ScansResource(ScanRequestParser parser, IntakeService intake, ScanStore scans, ReachConfig config,
                         SecurityIdentity identity, IntakeMetrics metrics) {
        this.parser = parser;
        this.intake = intake;
        this.scans = scans;
        this.config = config;
        this.identity = identity;
        this.metrics = metrics;
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed("scan-submitter")
    public Response submit(byte[] body) {
        ParseResult parsed = parser.parse(body);
        if (parsed instanceof ParseResult.Invalid invalid) {
            metrics.rejected(Caller.Source.REST, invalid.code().name());
            throw new ProblemException(Problem.schemaInvalid(invalid.violations()));
        }
        ParseResult.Valid valid = (ParseResult.Valid) parsed;
        Caller caller = new Caller(Caller.Source.REST, identity.getPrincipal().getName(), identity.getRoles());
        SubmitResult r;
        try {
            r = intake.submit(valid.request(), caller);
        } catch (RequestRejectedException e) {
            metrics.rejected(Caller.Source.REST, "SCHEMA_INVALID");
            throw e;
        }
        metrics.accepted(Caller.Source.REST, r.duplicate());
        return Response.accepted(new ScanDtos.Accepted(r.scanId(), r.requestUid(), r.status(), r.duplicate(),
                        r.priorityClass(), r.dtWriteBack(), r.createdAt(), r.warnings()))
                .location(URI.create("/api/v1/scans/" + r.scanId()))
                .build();
    }

    @GET
    @Path("/{scanId}")
    @RolesAllowed("results-reader")
    public ScanDtos.Scan get(@PathParam("scanId") String scanId) {
        ScanRow row = parseId(scanId).flatMap(scans::findById).orElseThrow(() -> new ProblemException(
                Problem.of(404, "SCAN_NOT_FOUND", "Scan not found", "no scan with id " + scanId)));
        return toScan(row);
    }

    @GET
    @RolesAllowed("results-reader")
    public ScanDtos.ScanPage list(@QueryParam("repo") String repo, @QueryParam("commit") String commit,
                                  @QueryParam("status") String status, @QueryParam("page_size") Integer pageSize,
                                  @QueryParam("cursor") String cursor) {
        String org = null;
        String name = null;
        if (repo != null) {
            String[] parts = repo.split("/", -1);
            if (parts.length != 2 || parts[0].isEmpty() || parts[1].isEmpty()) {
                throw new ProblemException(Problem.invalidParameter("repo", "must be <org>/<repo>"));
            }
            org = parts[0];
            name = parts[1];
        }
        if (commit != null && !commit.matches("[0-9a-f]{40}")) {
            throw new ProblemException(Problem.invalidParameter("commit", "must be a full 40-hex commit SHA"));
        }
        Set<ScanStatus> statuses = parseStatuses(status);
        int max = config.results().pageSize();
        int size = pageSize == null ? max : pageSize;
        if (size < 1 || size > max) {
            throw new ProblemException(Problem.invalidParameter("page_size", "must be between 1 and " + max));
        }
        int filterHash = Objects.hash(repo, commit, statuses);
        Cursor after = null;
        if (cursor != null) {
            after = Cursor.decode(cursor).filter(c -> c.filterHash() == filterHash).orElseThrow(() ->
                    new ProblemException(Problem.invalidParameter("cursor", "unknown cursor or cursor from other filters")));
        }

        List<ScanRow> rows = scans.list(new ScanListFilter(org, name, commit, statuses,
                after == null ? null : after.createdAt(), after == null ? null : after.id()), size + 1);
        String next = null;
        if (rows.size() > size) {
            rows = rows.subList(0, size);
            ScanRow last = rows.getLast();
            next = new Cursor(last.createdAt(), last.id(), filterHash).encode();
        }
        List<ScanDtos.ScanItem> items = rows.stream().map(r -> new ScanDtos.ScanItem(r.id(), r.requestUid(),
                r.status(), r.reason(), new ScanDtos.Repo(r.repoUrl(), r.commitSha()),
                new ScanDtos.DtProjectRef(r.dtProjectName(), r.dtProjectVersion()), r.createdAt(), r.finishedAt()))
                .toList();
        return new ScanDtos.ScanPage(items, next);
    }

    private static Set<ScanStatus> parseStatuses(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        Set<ScanStatus> statuses = EnumSet.noneOf(ScanStatus.class);
        for (String s : status.split(",")) {
            try {
                statuses.add(ScanStatus.valueOf(s.strip()));
            } catch (IllegalArgumentException e) {
                throw new ProblemException(Problem.invalidParameter("status", "unknown status " + s.strip()));
            }
        }
        return statuses;
    }

    private static Optional<UUID> parseId(String id) {
        try {
            return Optional.of(UUID.fromString(id));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static ScanDtos.Scan toScan(ScanRow r) {
        List<String> formats = StreamSupport.stream(r.options().path("formats").spliterator(), false)
                .map(n -> n.asText()).toList();
        List<Warning> warnings = new ArrayList<>();
        r.options().path("warnings").forEach(w -> warnings.add(new Warning(w.path("code").asText(),
                w.path("detail").asText())));
        return new ScanDtos.Scan(r.id(), r.requestUid(), r.status(), r.reason(), r.reasonDetail(), r.attempt(),
                new ScanDtos.Repo(r.repoUrl(), r.commitSha()),
                new ScanDtos.DtProject(r.dtProjectName(), r.dtProjectVersion(), r.dtProjectUuid()),
                r.priorityClass(), r.dtWriteBack(), formats, r.createdAt(), r.enqueuedAt(), r.claimedAt(),
                r.finishedAt(), null, r.reusedFrom(), r.imageDigest(), warnings);
    }
}
