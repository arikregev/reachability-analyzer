# reachability-pod: Decision Log

This file exists for traceability. It records every architectural decision for reachability-pod: what was decided, who decided it, and why. Decisions stated by Arik (owner) are Accepted. Design choices made by the architecture team are Proposed until Arik reviews them. The design itself is in [architecture.md](architecture.md).

## Index

| ID | Title | Status | Decided by | Date |
|---|---|---|---|---|
| D-001 | Stack: Java 25, Quarkus 3.40 LTS, Postgres 18 | Accepted | Arik | 2026-10-25 |
| D-002 | Input contract: OCSF Scan Activity | Accepted | Arik | 2026-10-25 |
| D-003 | Dependency-Track 5.x as vulnerability source of truth | Accepted | Arik | 2026-10-25 |
| D-004 | Configurable DT write-back, default OFF | Accepted | Arik | 2026-10-25 |
| D-005 | Triggers: OCSF via REST or Kafka only | Accepted | Arik | 2026-10-25 |
| D-006 | Async pipeline on a Postgres queue | Accepted | Architecture team, accepted by Arik | 2026-10-25 |
| D-007 | Fully configurable prioritisation | Accepted | Arik | 2026-10-25 |
| D-008 | Two-stage fetch/scan isolation | Accepted | Architecture team, accepted by Arik | 2026-10-25 |
| D-009 | Pod hardening without Kata | Accepted | Arik | 2026-10-25 |
| D-010 | Polyglot scanner image | Accepted | Arik | 2026-10-25 |
| D-011 | Artifactory as the only dependency source | Accepted | Arik | 2026-10-25 |
| D-012 | Day-1 languages | Accepted | Arik | 2026-10-25 |
| D-013 | Reachability engines per language | Accepted | Architecture team, accepted by Arik | 2026-10-25 |
| D-014 | Vulnerable-symbol knowledge base | Accepted | Architecture team, accepted by Arik | 2026-10-25 |
| D-015 | Verdict model and format mapping | Accepted | Architecture team, accepted by Arik | 2026-10-25 |
| D-016 | Outputs on S3: OCSF and VEX | Accepted | Arik | 2026-10-25 |
| D-017 | Completion event with presigned links | Accepted | Arik | 2026-10-25 |
| D-018 | Bulk results query API | Accepted | Arik | 2026-10-25 |
| D-019 | One GitHub repo = one DT project | Accepted | Arik | 2026-10-25 |
| D-020 | Scale target 10,000 scans/day | Accepted | Architecture team, accepted by Arik | 2026-10-25 |
| D-021 | Credentials and secrets | Superseded by D-032 | Architecture team (proposed) | 2026-10-25 |
| D-022 | Snyk as a later expansion | Accepted | Arik | 2026-10-25 |
| D-023 | Messaging: plain Apache Kafka, JSON Schema | Accepted | Arik | 2026-10-25 |
| D-024 | AuthN/Z: any OIDC provider | Accepted | Arik | 2026-10-25 |
| D-025 | Configurable retention | Accepted | Arik | 2026-10-25 |
| D-026 | Single tenant, sys-admin console only | Accepted | Arik | 2026-10-25 |
| D-027 | Docs location | Accepted | Arik | 2026-10-25 |
| D-028 | Contract details v1 and versioning policy | Accepted | Architecture team, accepted by Arik | 2026-10-25 |
| D-029 | Flyway for Postgres schema migrations | Rejected, superseded by D-031 | Architecture team (proposed) | 2026-10-25 |
| D-030 | One Maven multi-module repository | Accepted | Architecture team, accepted by Arik | 2026-10-25 |
| D-031 | Liquibase for Postgres schema migrations | Accepted | Arik | 2026-10-25 |
| D-032 | Secrets from HashiCorp Vault; static DT API key | Accepted (DT key); Proposed (other secrets) | Arik; Architecture team (proposed) | 2026-10-25 |
| D-033 | Intake implementation: plain JDBC, shared schema validator, fail-stop Kafka intake | Proposed | Architecture team | 2026-10-25 |
| D-034 | Every stage ships mock backends and a local Postgres | Accepted | Arik | 2026-10-25 |
| D-035 | scan-controller core: leader lock, claim loop, DT read path, outbox relay | Proposed | Architecture team | 2026-10-25 |
| D-036 | Fetch and scan Jobs: launch, watch, failure mapping, GitHub App tokens | Proposed | Architecture team | 2026-10-25 |
| D-037 | A scan's repository must match its DT project before write-back | Accepted | Delegated by Arik | 2026-10-25 |
| D-038 | Postgres retention: reachability-api drops whole monthly partitions | Accepted | Delegated by Arik | 2026-10-25 |
| D-039 | Intake hardening: OIDC audience, request size limit, REQUEST_MISMATCH warning | Accepted | Delegated by Arik | 2026-10-25 |
| D-040 | Exfiltration through package-manager proxies: mitigate and accept the rest | Accepted | Delegated by Arik | 2026-10-25 |
| D-041 | Review follow-ups: supply-chain hygiene, local stack, declined items | Accepted | Delegated by Arik | 2026-10-25 |
| D-042 | Scan runner: input and summary contracts, module detection, verdict merge, exit codes | Accepted | Delegated by Arik | 2026-10-25 |
| D-043 | Java engine: SootUp CHA with lambda edges; scanner image on UBI 10 | Accepted | Delegated by Arik | 2026-10-25 |
| D-044 | Per-scan Artifactory token minted and revoked by the controller | Accepted | Delegated by Arik | 2026-10-25 |

D-037 to D-041 close the post-merge review of PRs #1 and #2. D-042 to D-044 come with the scanner and the Java engine, under the same delegation. Arik asked the reviewer to "solve all how you see fit, explain yourself in the decision files", so they are recorded as Accepted under that delegation; any of them can be superseded the usual way.

Arik reviewed the Proposed decisions on 2026-10-25. He accepted D-006, D-013, D-014, D-015, D-020, D-028 and D-030, accepted D-008 after discussion, and replaced D-021 with D-032 and D-029 with D-031.

## Decisions

### D-001 Stack: Java 25, Quarkus 3.40 LTS, Postgres 18
- Status: Accepted / Decided by: Arik / Date: 2026-10-25
- Context: The service needs a runtime and a store for queue, state and knowledge base.
- Decision: Java 25 on Quarkus 3.40 LTS. Postgres 18, used only where needed: queue, scan state, symbol KB (all three need it).
- Alternatives considered:
  - No separate database: rejected; queue, state and KB need durable transactional storage.
- Consequences:
  - Positive: one language and one datastore for all services.
  - Negative: Postgres carries the queue load; it must be sized for 10k scans/day (D-020).
- Source: Arik, 2026-10-25: Java 25, Quarkus 3.40 LTS, Postgres 18 only where needed.

### D-002 Input contract: OCSF Scan Activity
- Status: Accepted / Decided by: Arik / Date: 2026-10-25
- Context: Requests arrive by REST and by Kafka. Both need one contract.
- Decision: REST body and Kafka record are both OCSF 1.9.0 Scan Activity (class_uid 6007). Repo, commit, DT project (name + version; UUID optional) and options go in `unmapped.reachability`, a versioned JSON Schema. Any activity_id is accepted.
- Alternatives considered:
  - repo@commit plus a DT project UUID: replaced; callers know name + version, not UUIDs (see Superseded).
- Consequences:
  - Positive: one schema for both channels; aligned with OCSF outputs (D-016, D-017).
  - Negative: service must resolve name + version to a UUID via GET /api/v1/project/lookup.
  - Negative: the `unmapped.reachability` schema must be versioned and maintained by us.
- Source: Arik, 2026-10-25: approved the OCSF format and the unmapped block.

### D-003 Dependency-Track 5.x as vulnerability source of truth
- Status: Accepted / Decided by: Arik / Date: 2026-10-25
- Context: The service judges reachability per known finding. It needs one authoritative finding source.
- Decision: Dependency-Track 5.x, `/api/v1` endpoints. Findings via GET /api/v1/finding/project/{uuid}. Finding identity = (componentUuid, vulnerabilityUuid).
- Alternatives considered:
  - DT `/api/v2`: rejected; v2 has no findings, BOM, VEX or analysis endpoints.
  - Findings from a separate SCA pod: replaced by DT (see Superseded).
- Consequences:
  - Positive: reuses the organisation's existing SBOM and vulnerability data.
  - Negative: v1 is marked "phased out as v2 replacements appear" (DT ADR-007). None of our endpoints is deprecated today; track DT releases.
  - Negative: DT has no finding UUID; we must key on the pair. Inactive findings are hidden by default.
- Source: Arik, 2026-10-25: DT 5.x. API version from research.

### D-004 Configurable DT write-back, default OFF
- Status: Accepted / Decided by: Arik / Date: 2026-10-25
- Context: Verdicts could be written back into DT. Arik wants this ready but not forced.
- Decision: Write-back mode is configurable, default OFF, with per-org/repo override. Modes:
  - OFF.
  - COMMENT: PUT /api/v1/analysis, analysisDetails + comment only.
  - VEX: POST /api/v1/vex multipart CycloneDX VEX; async, returns a token polled at GET /api/v1/event/token/{uuid}.
  - ANALYSIS: PUT /api/v1/analysis with NOT_AFFECTED + CODE_NOT_REACHABLE for NOT_REACHABLE verdicts.
- Alternatives considered:
  - Always write back: rejected; Arik wants it optional.
- Consequences:
  - Positive: DT users can see verdicts without a new tool.
  - Negative: write modes need VULNERABILITY_ANALYSIS_UPDATE on the DT key.
  - Risk (UNVERIFIED): DT vuln policies (/api/v2/vuln-policies) may overwrite our analysis.
- Source: Arik, 2026-10-25: "be ready for that integration, but make it configurable".

### D-005 Triggers: OCSF via REST or Kafka only
- Status: Accepted / Decided by: Arik / Date: 2026-10-25
- Context: Scans could start from DT events or from explicit requests.
- Decision: The only trigger is an OCSF Scan Activity via REST or Kafka. The service does not consume DT notifications. Any service may emit requests.
- Alternatives considered:
  - DT 5 Kafka notification publisher (protobuf, at-least-once, BOM_PROCESSED may fire before findings exist): rejected by Arik.
  - Kept only as a possible future adapter, outside this service, that converts DT notifications to OCSF.
- Consequences:
  - Positive: one intake path; no protobuf, debounce or dedupe logic for DT events.
  - Negative: new DT vulnerabilities do not trigger re-scans unless an emitter sends a request.
- Source: Arik, 2026-10-25: triggers are OCSF Scan Activity only; no DT notification consumer.

### D-006 Async pipeline on a Postgres queue
- Status: Accepted (Arik, 2026-10-25) / Decided by: Architecture team, accepted by Arik / Date: 2026-10-25
- Context: Scans run for minutes. Intake must not hold that work.
- Decision: REST and Kafka intake only persist the request to a Postgres queue; the Kafka offset is committed only after the Postgres insert commits, so a crash before the commit redelivers the record and the request-uid unique key absorbs the duplicate. scan-controller claims work with `SELECT ... FOR UPDATE SKIP LOCKED`. Kafka never holds long work.
- Alternatives considered:
  - Kafka as the work queue: rejected; long tasks exceed poll intervals and cause consumer-group rebalances.
- Consequences:
  - Positive: durable queue with priorities and caps (D-007) in one transactional store.
  - Negative: Postgres becomes the throughput bottleneck; queue tables need indexing and partitioning.
- Source: Arik, 2026-10-25: requests must be queued. Postgres as the queue is our proposal.
- Clarified 2026-10-25: the offset commit timing was corrected from "committed immediately" to "committed only after the Postgres insert commits", to match architecture.md 3.1 and section 11. No change of intent.

### D-007 Fully configurable prioritisation
- Status: Accepted / Decided by: Arik / Date: 2026-10-25
- Context: Urgent scans must not wait behind bulk scans.
- Decision: Priority classes (e.g. critical, pr, default, bulk) with weights. Aging prevents starvation. Concurrency caps at global, org and repo level. Per-request priority in `unmapped.reachability`. Admin REST for policies; hot reload from DB.
- Alternatives considered:
  - Fixed FIFO queue: rejected; Arik wants it fully configurable.
- Consequences:
  - Positive: operators tune scheduling without redeploys.
  - Negative: more claim-query complexity; aging weights need tuning under load.
- Source: Arik, 2026-10-25: prioritisation fully configurable.

### D-008 Two-stage fetch/scan isolation
- Status: Accepted (Arik, 2026-10-25, after discussion: source is on the corporate GitHub; two-stage kept because the risk is third-party build code, not the corporate repo) / Decided by: Architecture team, accepted by Arik / Date: 2026-10-25
- Context: The scan builds untrusted code. That code must not reach GitHub or hold a GitHub token.
- Decision:
  - Stage 1, source-fetch Job in trusted namespace `reach-fetch`: clones the commit with recursive submodules using a GitHub App installation token, tars it to S3. Egress: GitHub and S3 only.
  - Stage 2, scan Job in untrusted namespace `reach-scan`: downloads the tarball via presigned GET, builds and analyses. Egress: Artifactory and S3 only; the S3 VPC endpoint policy is pinned to our bucket.
- Alternatives considered:
  - One Job with GitHub egress: rejected; GitHub egress from untrusted pods is an exfiltration channel.
- Consequences:
  - Positive: untrusted code never gets GitHub egress or a GitHub token.
  - Negative: two Jobs per scan; extra latency and one more S3 object per scan.
- Source: Proposed, from platform research.

### D-009 Pod hardening without Kata
- Status: Accepted / Decided by: Arik / Date: 2026-10-25
- Context: Builds run hostile code. The cluster has no Kata, and gVisor is unsupported on RHCOS.
- Decision: restricted-v2 SCC; restricted-v3 with hostUsers:false user namespaces when the cluster is OCP 4.20+. Dedicated tainted scanner MachineSet. EgressFirewall (cidrSelector for AWS S3 ranges, dnsName for Artifactory) plus default-deny NetworkPolicy. automountServiceAccountToken:false, no RBAC. Ephemeral-storage limits, read-only root FS. Job: activeDeadlineSeconds, ttlSecondsAfterFinished, backoffLimit 0. One Job per scan.
- Alternatives considered:
  - Kata Containers: rejected; unavailable on the cluster.
  - gVisor: rejected; unsupported on RHCOS.
  - Warm reused worker pools: rejected; they break isolation through cross-build contamination.
- Consequences:
  - Positive: uses only supported OpenShift features.
  - Negative: container isolation is weaker than a VM sandbox; the node pool and egress rules are the backstop.
  - UNVERIFIED: user namespaces on OCP 4.18/4.19 (TP in 4.17, GA in 4.20). Phase 0 checks the OCP version.
- Source: Arik, 2026-10-25: no Kata on the cluster. Hardening set from research.

### D-010 Polyglot scanner image
- Status: Accepted, base OS amended by D-043 (UBI 10) / Decided by: Arik / Date: 2026-10-25
- Context: The scan Job must build repos with any common package manager.
- Decision: One UBI 9 image with JDK 25 + Maven + Gradle; Python + pip/uv/Poetry (+ poetry-plugin-pypi-mirror); Go; Node 22 + npm/Yarn/pnpm; version managers (SDKMAN, fnm, uv-managed Pythons); the engines. Runs as an arbitrary UID. Weekly rebuild from a pinned manifest. Image digest recorded per scan. Pre-pulled by a DaemonSet.
- Alternatives considered:
  - Per-language images: not chosen; Arik requires all package managers, and polyglot repos need several toolchains in one Job.
- Consequences:
  - Positive: one image to harden, pin and pre-pull.
  - Negative: large image; weekly rebuild can change scan results, mitigated by the recorded digest.
- Source: Arik, 2026-10-25: support all package managers.

### D-011 Artifactory as the only dependency source
- Status: Accepted / Decided by: Arik / Date: 2026-10-25
- Context: Builds need dependencies. The untrusted pod must not reach public registries.
- Decision: Per-job short-lived read-only token (POST /access/api/v1/tokens, expiry about 2x the deadline). Config is injected without touching the repo:
  - Maven: MAVEN_ARGS `-gs/-s settings.xml`, mirrorOf `*`. Gradle: init.d script. Use the image's mvn/gradle, not wrappers.
  - Python: PIP_INDEX_URL; UV_DEFAULT_INDEX + UV_PYTHON_DOWNLOADS=never; POETRY_PYPI_MIRROR_URL.
  - Go: GOPROXY to Artifactory, GONOPROXY=none, GOSUMDB via Artifactory, GOFLAGS=-mod=mod, GOTOOLCHAIN=local.
  - JS: npm_config_registry; YARN_NPM_REGISTRY_SERVER.
- Alternatives considered:
  - Public registries: rejected; widens egress from untrusted pods.
- Consequences:
  - Positive: one audited dependency path; tokens are scoped and expire.
  - Negative: a repo `.npmrc` can override the registry; the egress block is the backstop.
- Source: Arik, 2026-10-25: JFrog Artifactory. Injection details from research.

### D-012 Day-1 languages
- Status: Accepted / Decided by: Arik / Date: 2026-10-25
- Context: Language scope sets engine and image work for phase 1.
- Decision: Function-level reachability on day 1 for Java (Maven, Gradle), Python, Go and JS/TS. Other languages are accepted but get import-level or UNKNOWN verdicts. C/C++ is UNKNOWN. The rest are expansions.
- Alternatives considered:
  - Java-first: replaced by four day-1 languages (see Superseded).
- Consequences:
  - Positive: covers the main ecosystems from the start.
  - Negative: four engines to spike and harden in phase 1.
- Source: Arik, 2026-10-25: four day-1 languages.

### D-013 Reachability engines per language
- Status: Accepted (Arik, 2026-10-25) / Decided by: Architecture team, accepted by Arik / Date: 2026-10-25
- Context: Each day-1 language needs a call-graph engine with a usable licence.
- Decision: All engines sit behind one `ReachabilityEngine` SPI.
  - Java: SootUp 3.0.x on bytecode, CHA/RTA default, Qilin points-to optional; classpath via `dependency:build-classpath` per module or a Gradle init script. Fallback OPAL.
  - Python: pinned in-house fork of PyCG over source + lockfile-installed deps. Fallback Joern pysrc2cpg. Last resort import scan.
  - Go: govulncheck v1.9 `-format json`, joined to DT findings on OSV aliases.
  - JS/TS: Jelly 0.13 with a generated `-v` vulnerabilities file, per workspace package. Fallback import-level.
- Alternatives considered:
  - Upstream PyCG: rejected; archived in 2023, so we fork and pin it.
  - PyXray: rejected; AGPL.
- Consequences:
  - Negative: SootUp is LGPL-2.1; legal check needed. Java 25 classfile support is UNVERIFIED.
  - Negative: Python dynamic features cause false negatives; NOT_REACHABLE confidence is lower. Joern site-packages resolution is UNVERIFIED.
  - Negative: Java DI, reflection and lambdas need entry-point models (phase 2).
- Source: Proposed, from engine research.

### D-014 Vulnerable-symbol knowledge base
- Status: Accepted (Arik, 2026-10-25) / Decided by: Architecture team, accepted by Arik / Date: 2026-10-25
- Context: Function-level verdicts need the vulnerable functions for each vulnerability. Public data is sparse.
- Decision: Postgres `kb` schema keyed by (purl/ecosystem package, vulnId). Sources in order: Go OSV symbols, GHSA vulnerable_functions, fix-commit method diffs (OSV FIX refs, SAP Project KB), manual curation. With no symbols, the verdict is package/class-level and labelled as such.
- Alternatives considered:
  - Eclipse Steady data: not used as a source; stale since 2022.
- Consequences:
  - Positive: own store; grows over time and supports overrides.
  - Negative: GHSA symbol data is sparse; many verdicts start at package level.
- Source: Proposed, from engine research.

### D-015 Verdict model and format mapping
- Status: Accepted (Arik, 2026-10-25) / Decided by: Architecture team, accepted by Arik / Date: 2026-10-25
- Context: Verdicts must map cleanly to OCSF, CycloneDX VEX and OpenVEX.
- Decision: Verdicts REACHABLE, POTENTIALLY_REACHABLE, NOT_REACHABLE, UNKNOWN, with reason codes (e.g. NO_SYMBOL_DATA, BUILD_FAILED, ENGINE_TIMEOUT, UNSUPPORTED_LANGUAGE). Mapping:
  - OCSF trait: Contributing / Mitigating.
  - CycloneDX: exploitable / in_triage / not_affected + code_not_reachable.
  - OpenVEX: affected / under_investigation / not_affected + vulnerable_code_not_in_execute_path.
- Alternatives considered: none recorded.
- Consequences:
  - Positive: failures are explicit (UNKNOWN + reason), never silent NOT_REACHABLE.
  - Negative: Jelly timeouts give partial results, reported as POTENTIALLY_REACHABLE.
- Source: Proposed.

### D-016 Outputs on S3: OCSF and VEX
- Status: Accepted / Decided by: Arik / Date: 2026-10-25
- Context: Consumers need results in standard formats.
- Decision: Results under `s3://<bucket>/<org>/<repo>/<commit>/<scanId>/` on AWS S3:
  - ocsf.json: OCSF 2002 Vulnerability Finding per finding; reachability in finding_info.traits; call path in unmapped.reachability.
  - cyclonedx-vex.json (CycloneDX 1.6), openvex.json, summary.json.
  - OCSF and VEX are always produced by default; a request may narrow the set.
- Alternatives considered: none recorded.
- Consequences:
  - Positive: SIEM (OCSF) and supply-chain (VEX) consumers are both served.
  - Negative: three result formats to keep consistent.
- Source: Arik, 2026-10-25: results to S3 in OCSF and VEX.

### D-017 Completion event with presigned links
- Status: Accepted / Decided by: Arik / Date: 2026-10-25
- Context: Requesters need to know when a scan is done and where results are.
- Decision: OCSF Scan Activity Completed (activity_id 2) on `appsec.reachability.results.v1` with presigned GET links. Error and Cancelled go to the status topic. Link TTL configurable, default 24h. A REST re-sign endpoint is always available.
- Alternatives considered:
  - STS/IRSA-signed links for TTL over 12h: rejected; URL life is capped at the session end (AssumeRoleWithWebIdentity 1-12h).
- Consequences:
  - Positive: consumers fetch results without AWS credentials.
  - Negative: TTL over 12h requires long-term IAM user keys (max 7 days); a security trade-off.
- Source: Arik, 2026-10-25: announce completion with a presigned link. TTL limits from research.

### D-018 Bulk results query API
- Status: Accepted / Decided by: Arik / Date: 2026-10-25
- Context: Dashboards need results across many projects.
- Decision: `POST /api/v1/results/query`. Filters: DT project name + version (primary, list), repo list, vulnId/CVE, purl, verdict, time range. Sync, cursor-paged, up to a limit. Larger queries become an async export job: NDJSON/OCSF to S3, presigned link, Kafka event on the exports topic.
- Alternatives considered:
  - Per-team views in the service: rejected; the external AppSec dashboard (out of scope) does any per-team division.
- Consequences:
  - Positive: one API serves dashboards and exports.
  - Negative: export jobs add state (`export` table) and S3 objects.
- Source: Arik, 2026-10-25: bulk results API; dashboard is out of scope.

### D-019 One GitHub repo = one DT project
- Status: Accepted / Decided by: Arik / Date: 2026-10-25
- Context: Repos may hold many modules and submodules. DT holds one SBOM per project.
- Decision: One GitHub repo = one DT project. Submodules are cloned recursively. DT has one merged SBOM. The Job detects modules per ecosystem and maps finding purls to modules through the SBOM dependency graph (GET /api/v1/bom/cyclonedx/project/{uuid}).
- Alternatives considered: none recorded.
- Consequences:
  - Positive: simple request contract; one project per scan.
  - Negative: purl-to-module mapping depends on the SBOM dependency graph being complete.
- Source: Arik, 2026-10-25: one repo per DT project, merged SBOM.

### D-020 Scale target 10,000 scans/day
- Status: Accepted (Arik, 2026-10-25) / Decided by: Architecture team, accepted by Arik / Date: 2026-10-25
- Context: Sizing drives node pools, queue design and cleanup.
- Decision: Target 10,000 scans/day (~7/min average). Estimate: ~170 concurrent at 3x peak with an 8-minute median, ~680 vCPU peak at 4 vCPU/Job. Dedupe on (repo, commit, DT findings hash). Library summary cache. Job TTL cleanup.
- Alternatives considered: none recorded.
- Consequences:
  - Positive: 10k Jobs/day is within OpenShift limits.
  - Negative: finished Jobs, Pods and Events load etcd; TTL 300-3600s after status is collected.
  - UNVERIFIED: all figures are estimates; phase 3 load test measures them.
- Source: Arik, 2026-10-25: 10,000 scans/day. Sizing, dedupe and caching are our proposal.

### D-021 Credentials and secrets
- Status: Superseded by D-032 / Decided by: Architecture team (proposed) / Date: 2026-10-25
- Context: The service talks to DT, GitHub, Artifactory and AWS.
- Decision: DT service-account API key with a rotation job. GitHub App installation tokens, fetch Job only. Artifactory per-job tokens. AWS via IRSA/STS for services. Secrets in OpenShift Secrets / External Secrets.
- Alternatives considered:
  - DT workload identity federation (v2 POST /oauth/token, RFC 8693): deferred to later; UNVERIFIED on OpenShift.
- Consequences:
  - Positive: no long-lived credential reaches untrusted pods.
  - Negative: DT SA keys must expire (max 366 days); rotation is mandatory.
- Source: Proposed.

### D-022 Snyk as a later expansion
- Status: Accepted / Decided by: Arik / Date: 2026-10-25
- Context: Snyk offers reachability for some ecosystems.
- Decision: Snyk is a later expansion (phase 3), added as another `ReachabilityEngine` using `snyk test --reachability` (Preview).
- Alternatives considered:
  - Snyk as day-1 commercial benchmark/engine: moved to later expansion (see Superseded).
- Consequences:
  - Positive: day 1 has no commercial dependency.
  - Negative: Snyk reachability is Preview; its status must be rechecked at phase 3.
- Source: Arik, 2026-10-25: Snyk later, as an expansion.

### D-023 Messaging: plain Apache Kafka, JSON Schema
- Status: Accepted / Decided by: Arik / Date: 2026-10-25
- Context: Requests, status, results and exports travel on Kafka.
- Decision: Plain Apache Kafka, not tied to a vendor distribution. JSON payloads validated in-service against versioned JSON Schemas. Schema registry optional and configurable. Topics: appsec.reachability.requests.v1, .status.v1, .results.v1, .exports.v1, and a requests DLQ.
- Alternatives considered:
  - Vendor-specific Kafka distribution: rejected; Arik wants plain Apache Kafka.
- Consequences:
  - Positive: portable across Kafka deployments.
  - Negative: schema enforcement lives in the service, not the broker.
- Source: Arik, 2026-10-25: plain Apache Kafka; schema registry optional.

### D-024 AuthN/Z: any OIDC provider
- Status: Accepted / Decided by: Arik / Date: 2026-10-25
- Context: REST and admin endpoints need authentication.
- Decision: Any OIDC provider, configurable (quarkus-oidc). Roles: scan-submitter, results-reader, sys-admin. Kafka security (SASL/mTLS) configurable.
- Alternatives considered:
  - A fixed identity provider: rejected; Arik wants it configurable.
- Consequences:
  - Positive: fits any corporate IdP.
  - Negative: role claims mapping must be configured per provider.
- Source: Arik, 2026-10-25: make it configurable.

### D-025 Configurable retention
- Status: Accepted / Decided by: Arik / Date: 2026-10-25
- Context: Results and state grow with 10k scans/day.
- Decision: Retention is configurable. Defaults: S3 1 year (lifecycle rule); Postgres 90 days (monthly partitions).
- Alternatives considered: none recorded.
- Consequences:
  - Positive: partition drops make Postgres cleanup cheap.
  - Negative: presigned links and re-sign stop working once S3 objects expire.
- Source: Arik, 2026-10-25: accepted the recommended defaults, configurable.

### D-026 Single tenant, sys-admin console only
- Status: Accepted / Decided by: Arik / Date: 2026-10-25
- Context: The service needs an operator view. AppSec dashboards exist elsewhere.
- Decision: Single tenant. The only UI is a sys-admin console in reachability-api (Quarkus Qute + htmx, admin role): queue view, priority policies, concurrency caps, write-back config, KB overrides.
- Alternatives considered:
  - AppSec dashboards in the service: rejected; out of scope, served by the bulk query API (D-018).
- Consequences:
  - Positive: small UI surface, no separate frontend.
  - Negative: multi-tenancy would need a new decision.
- Source: Arik, 2026-10-25: single tenant; only a sys-admin UI.

### D-027 Docs location
- Status: Accepted / Decided by: Arik / Date: 2026-10-25
- Context: Design docs need a durable home.
- Decision: Docs live in `docs/` of the service's GitHub repo (architecture.md, decisions.md). Until Arik creates the repo, they are kept in project files.
- Alternatives considered: none recorded.
- Consequences:
  - Positive: docs version with the code.
  - Negative: files must be moved when the repo exists.
- Source: Arik, 2026-10-25: docs in the repo's docs/ folder.

### D-028 Contract details v1 and versioning policy
- Status: Accepted (Arik, 2026-10-25) / Decided by: Architecture team, accepted by Arik / Date: 2026-10-25
- Context: architecture.md 5.1 to 5.4 named the endpoints and topics but left wire details open: error bodies, response bodies, status event layout, reason codes, DLQ headers, the results.v1 key, write-back and priority rules, bulk query fields, versioning and emitter auth. Integrators could not build without guessing.
- Decision: The v1 details in architecture.md 5.4a and the JSON Schema `docs/contracts/scan-request.v1.schema.json`:
  - REST errors are RFC 9457 problem+json with `code` and `errors[]`; 400 SCHEMA_INVALID, 401, 403, 404, 409, 422 EXPORT_REQUIRED.
  - status.v1 uses OCSF activity_id 1/2/3/6 and 99 for intermediate states; failures carry `status_detail` and `unmapped.reachability.reason`, from a fixed set of scan-level reason codes (DT_UUID_MISMATCH fails at claim, no retry; DT_UNAVAILABLE retryable).
  - `links` omits formats that were not requested. Re-sign `ttl` is an ISO 8601 duration capped by `reach.s3.link-ttl-max` (proposed P7D).
  - DLQ headers `reach-error-code`, `reach-error-detail`, `reach-schema-version`, `reach-received-at`. results.v1 key `<dt_project_name>@<dt_project_version>`.
  - Write-back ranks OFF < COMMENT < VEX < ANALYSIS; a request may pick an equal or lower mode. Classes above `default` need OIDC role `reach-priority-<class>`, else the request is downgraded with a warning.
  - Versioning: additive changes only within v1; breaking changes ship as `.v2` topics and `/api/v2` alongside v1 for at least 6 months (proposed); `schema_version` lives in `unmapped.reachability`; changes are announced in the docs changelog and here.
  - Emitters use the OIDC client-credentials grant; Kafka principals `reach-emitter-<team>`, consumer groups prefixed `reach-consumer-<team>`.
- Alternatives considered:
  - Leave details to implementation in phase 1: rejected; emitters and consumers need a fixed contract before the phase 0 freeze (R15).
  - Hard-reject a request that asks for a higher priority or write-back mode than allowed: rejected; downgrade with a warning keeps CI pipelines running, matching the existing `default` fallback (D-007).
  - Null entries in `links` for formats not requested: rejected; omission keeps one rule for absent formats.
- Consequences:
  - Positive: integrators can build and validate against one schema and one set of examples.
  - Positive: breaking changes have a defined path and a minimum overlap.
  - Negative: Kafka records carry no verifiable producer identity, so priority grants for Kafka emitters need config (`reach.auth.kafka-priority-classes`), not OIDC roles.
  - Negative: running v1 and v2 side by side doubles topics and endpoints for at least 6 months.
- Source: Proposed, to close the contract gaps listed in integration-guide.md. Values marked proposed await Arik's review.

### D-029 Flyway for Postgres schema migrations
- Status: Rejected by Arik, superseded by D-031 / Decided by: Architecture team (proposed) / Date: 2026-10-25
- Context: The `scan` and `kb` schemas change over time and use monthly partitions (D-025). Zero-downtime upgrades need ordered, versioned migrations.
- Decision: Use Flyway through the `quarkus-flyway` extension. reachability-api runs migrations at startup, guarded by Flyway's lock. scan-controller and symbol-kb-builder only validate. Migrations are expand-then-contract only, so the old and new versions can run side by side during a rollout. pg_partman, or a scheduled job in scan-controller, creates and drops the monthly partitions.
- Alternatives considered:
  - Liquibase: also supported by Quarkus; rejected because its XML/YAML changelogs add no value over plain SQL here.
  - Hibernate `schema-generation`: rejected; it is unsafe for production and cannot handle partitions.
- Consequences:
  - Positive: plain SQL is reviewable, and the schema history table gives a version record per environment.
  - Negative: the expand-then-contract discipline must be enforced in reviews.
- Source: Proposed. It closes an open question from operations-guide.md and CONTRIBUTING.md.

### D-030 One Maven multi-module repository
- Status: Accepted (Arik, 2026-10-25) / Decided by: Architecture team, accepted by Arik / Date: 2026-10-25
- Context: The service has several deployables (reachability-api, scan-controller, scanner job, symbol-kb-builder), shared contracts and per-language engines. Docs live in `docs/` of the same repo (D-027).
- Decision: Use one GitHub repo with a Maven multi-module build (Quarkus BOM 3.40, Java 25). The modules are `contracts` (JSON Schemas and generated DTOs), `common`, `reachability-api`, `scan-controller`, `scanner` (the Job entrypoint, plus the source-fetch mode), `engines/engine-spi`, `engines/java-sootup`, `engines/python-pycg`, `engines/go-govulncheck`, `engines/js-jelly`, `symbol-kb-builder`, `deploy/` (Helm chart or Kustomize for OpenShift) and `images/` (the polyglot image Containerfile and its pinned tool manifest). Maven, not Gradle, because it is the Quarkus default and the scanner image already carries it.
- Alternatives considered:
  - Gradle multi-project: works, but gives no advantage for this team.
  - One repo per service: rejected; contracts and the engine SPI would drift between repos.
- Consequences:
  - Positive: one version line, and contract changes are checked at compile time across all modules.
  - Negative: CI must build only the modules that changed to stay fast.
- Source: Proposed. It closes an open question from CONTRIBUTING.md.

### D-031 Liquibase for Postgres schema migrations
- Status: Accepted / Decided by: Arik / Date: 2026-10-25. Supersedes D-029.
- Context: The `scan` and `kb` schemas change over time and use monthly partitions (D-025). Zero-downtime upgrades need ordered, versioned migrations.
- Decision: Use Liquibase through the `quarkus-liquibase` extension. Changelogs are written in SQL-formatted or YAML changesets under `src/main/resources/db/changelog/`. reachability-api applies them at startup, guarded by Liquibase's lock table. scan-controller and symbol-kb-builder only validate. Changes are expand-then-contract only, so the old and new versions can run side by side. Monthly partitions are created and dropped by pg_partman or by a scheduled job in scan-controller (still open, E-8).
- Alternatives considered:
  - Flyway (D-029): rejected by Arik in favour of Liquibase.
  - Hibernate schema generation: unsafe for production and cannot handle partitions.
- Consequences:
  - Positive: rollback blocks, preconditions and contexts per environment.
  - Negative: the expand-then-contract rule must be enforced in code review.
- Source: Arik, 2026-10-25: "i prefer liquibase".

### D-032 Secrets from HashiCorp Vault; static DT API key
- Status: Accepted for the DT API key (Arik). Proposed for the other secrets. / Date: 2026-10-25. Supersedes D-021.
- Context: Arik confirmed a HashiCorp Vault that holds the Dependency-Track API key as a static secret.
- Decision:
  - reachability-api and scan-controller read the DT API key from Vault KV v2. They authenticate with the Vault Kubernetes auth method, using their own ServiceAccount token in the trusted `reach` namespace, through the `quarkus-vault` extension (Quarkiverse) as a config source. The key is re-read on a configurable interval (`reach.dt.api-key-refresh`, proposed PT5M), so a key changed in Vault takes effect without a restart.
  - The service does not rotate the DT key. The Vault owner does. Because DT 5 service-account keys expire (max 366 days), the service exposes the metric `reach_dt_apikey_expiry_days` and alerts before expiry. A non-expiring DT team API key avoids that alert.
  - Proposed: other long-lived secrets also live in Vault. These are the GitHub App private key, the IAM user signing keys, the Artifactory token-minting credential, and the Postgres and Kafka credentials.
  - Unchanged from D-021: short-lived per-job credentials (GitHub installation token, Artifactory token, presigned URLs) are minted per Job and passed in per-job Kubernetes Secrets. Job pods never talk to Vault and get no ServiceAccount token. AWS access for services uses IRSA/STS.
- Alternatives considered:
  - A DT key rotation CronJob through the DT v2 service-accounts API (D-021): dropped, because Vault owns the key.
  - Vault Agent Injector sidecars: possible, but the Quarkus config source needs no sidecar and supports refresh.
  - DT workload identity federation: still a later option (UNVERIFIED on OpenShift).
- Consequences:
  - Positive: one secret store and no rotation code in this service.
  - Negative: Vault is a runtime dependency for the services. Startup fails if Vault is unreachable, and running pods keep the last value they read.
- Source: Arik, 2026-10-25: "assume you have access to hashicorp vault that has the DT api key statically". Extending Vault to the other secrets is our proposal.

### D-033 Intake implementation: plain JDBC, shared schema validator, fail-stop Kafka intake
- Status: Proposed / Decided by: Architecture team / Date: 2026-10-25
- Context: The first code (reachability-api intake) needs a persistence style, a JSON Schema validator and a rule for Kafka records that cannot be stored.
- Decision:
  - Persistence is plain JDBC through Agroal with JTA transactions (`@Transactional`), with no ORM. The queue relies on partitioned tables, `FOR UPDATE SKIP LOCKED`, `ON CONFLICT` and advisory locks, which read more clearly as SQL.
  - Requests are validated with networknt `json-schema-validator` 1.5.x (JSON Schema 2020-12, format assertions on). The 2.x and 3.x lines need Jackson 3, and Quarkus 3.x uses Jackson 2.
  - `docs/contracts/*.schema.json` is the only copy of each schema. The `contracts` module packages it into its jar, so the docs and the running code cannot drift. REST and Kafka use the same parser.
  - Rules that the schema cannot express are checked in the service and reported like schema violations: `repo.url` must be `https://<host>/<org>/<repo>[.git]` on a host in `reach.github.allowed-hosts`, with no credentials, port, query or fragment. `link_ttl` must be positive.
  - Kafka intake: an invalid record goes to the DLQ. A Postgres outage is retried with exponential backoff for up to 5 minutes. After that the channel stops (`failure-strategy=fail`) and readiness turns DOWN, so the pod restarts without committing the offset. Records are never sent to the DLQ because of an infrastructure failure.
  - Kafka records that are valid but fail for any reason other than a Postgres outage (a data error from Postgres, for example) go to the DLQ with code `UNPROCESSABLE`, so one poison record cannot stop intake. A failure raised outside our JDBC calls, such as the transaction interceptor's commit during a failover, is classified by its cause chain: the first `SQLException` decides by its SQLState, and a `jakarta.transaction` exception without one counts as an outage. Outages are retried, never dead-lettered. When no priority class is enabled at all, intake raises a distinct configuration error: REST answers 503 `MISCONFIGURED`, and Kafka retries like an outage so no record is lost while an operator fixes it. The parser also rejects U+0000 in any string (Postgres `jsonb` cannot store it) and a `link_ttl` that overflows `Duration`.
  - Priority fails closed: the fallback class is `default`, or the lowest-weight enabled class if `default` is disabled, and every class above the fallback needs the role or the Kafka allow-list. No enabled class at all is a 503 (REST) or a retried outage (Kafka).
  - Org and repo are lower-cased at intake (`repo_url` is kept as sent). ORG and REPO keys of `scan.concurrency_limit` and `scan.writeback_policy` are lower-case by CHECK constraint (changeset 004, which lower-cases existing keys), so the claim query and the write-back lookup compare them directly, without `lower()`.
  - The scan's first attempt is `attempt = 1`. The first status event (QUEUED, Started) is written to the outbox in the intake transaction.
  - New monthly partitions of `scan.scan` are created by the SQL function `scan.ensure_monthly_partitions`. It runs from Liquibase, at start-up and daily (`reach.partitions.*`).
- Alternatives considered:
  - Hibernate ORM with Panache: rejected for the queue tables (see above). It may still be used for admin CRUD later.
  - Sending to the DLQ after N failed inserts: rejected, because a Postgres outage would move valid requests to the DLQ.
  - pg_partman for partitions: rejected, because it is an extra extension to install on managed Postgres.
- Consequences:
  - Positive: SQL matches architecture.md 6.1 and 6.2 directly. A schema change in the docs is a code change.
  - Negative: SQL mapping code is hand-written. A long Postgres outage stops Kafka intake until Postgres is back.
- Source: architecture team, implementation of PR "intake skeleton".

### D-034 Every stage ships mock backends and a local Postgres
- Status: Accepted / Decided by: Arik / Date: 2026-10-25
- Context: Each implementation stage must be testable without the real external systems.
- Decision:
  - Each stage adds mocks for the external systems it touches, and the docs say how to use them:
    - Postgres comes from Quarkus Dev Services (Testcontainers) in tests, and from `deploy/local/docker-compose.yml` for manual runs.
    - Kafka uses in-memory channels in tests, and a single-node Apache Kafka in the compose stack, with the 5.4 topics created.
    - The identity provider is `deploy/local/token.sh`, which mints RS256 tokens with chosen roles. The `local` profile verifies them with a local public key.
    - Planned for later stages: WireMock stubs for Dependency-Track and GitHub (PR 3), a dev-mode Vault (PR 3), an Artifactory stub (PR 4), and a local S3 emulator (PR 5).
- Alternatives considered:
  - A shared test environment: rejected, because tests would depend on its state and availability.
  - A containerised mock OAuth server: possible, but the token script needs no image and works offline.
- Consequences:
  - Positive: `mvn verify` needs only Docker, and a full manual run needs only Docker Compose.
  - Negative: mocks can drift from the real APIs, so stubs are recorded from the documented API shapes and reviewed when an API version changes.
- Source: Arik, 2026-10-25: "Per each stage, write a mock backend and raise local db for testing".

### D-035 scan-controller core: leader lock, claim loop, DT read path, outbox relay
- Status: Proposed / Decided by: Architecture team / Date: 2026-10-25
- Context: The first controller PR implements the queue side of architecture.md 3.2, 6.2 and 8.1, without the Jobs, which come next.
- Decision:
  - Leader: a session-level `pg_try_advisory_lock(reach.leader.lock-key)` on one dedicated connection outside the Agroal pool, opened with `tcpKeepAlive` and a socket timeout, and checked every `reach.leader.check-interval` with `isValid(reach.leader.check-timeout)`. `isLeader()` reads a volatile flag, so no caller waits on a slow check. If that connection breaks, Postgres frees the lock and the replica steps down. A new leader resumes the FETCHING scans whose DT data was never stored. Only the leader claims, publishes the outbox, purges and reports the queue and outbox gauges (a standby reports NaN).
  - Stuck scans: every `reach.queue.sweep-interval` the leader also resumes FETCHING scans without DT data that were claimed more than `reach.queue.fetch-timeout` ago, for example after a Postgres error once the DT calls had succeeded. A set of in-flight scan ids keeps a sweep or a re-acquired leadership from fetching a scan twice. A malformed DT answer (no uuid, a bad `X-Total-Count`) is `DT_UNAVAILABLE`.
  - Claim: the 6.2 query. Orgs and repos without a `concurrency_limit` row fall back to `reach.queue.org-max-running` (unset) and `reach.queue.repo-max-running` (2). A batch is also capped by the free DT workers (`reach.dt.max-concurrent`). The claim, the FETCHING update and its status event share one transaction.
  - DT read path:
    - Project lookup by name and version, then a uuid check, then paged findings that follow `X-Total-Count`. The result goes to `scan.finding` (verdict empty) with `findings_hash`.
    - The HTTP calls run outside any transaction, and every state update is guarded by the expected status.
    - 404 on lookup gives `DT_PROJECT_NOT_FOUND`; a uuid mismatch gives `DT_UUID_MISMATCH`.
    - 5xx, 429, 401/403 and connection errors are `DT_UNAVAILABLE` and retryable. A key replaced in Vault then heals on retry.
  - Retry: attempt n is requeued with `not_before = now + backoff * 2^(n-1)`, keeping its class and `enqueued_at`. When `reach.retry.max-attempts` is used up, the scan fails with the same code.
  - DT key: read from Vault KV v2 `reach/dt` through the quarkus-vault config source, and re-read from config on every DT call, so the config source's cache period controls the refresh (D-032).
  - Outbox relay: batches in id order, using each row's topic, key and headers. A row is marked published only after the broker acknowledged it, within `reach.outbox.send-timeout`, and only while this replica still leads. Published rows are purged after `reach.outbox.retention`.
  - Schema: the Liquibase changelog moves to the `common` module. scan-controller fails start-up while any changeset is unapplied.
  - Interim: until the Jobs PR, a scan stops in FETCHING once its DT data is loaded.
- Alternatives considered:
  - Kubernetes Lease for leader election: rejected for now, because the advisory lock needs no RBAC and is tied to the same database the work lives in.
  - Treating DT 401/403 as permanent: rejected, because a rotated key fixes itself, and the retry limit still ends the scan.
- Consequences:
  - Positive: all controller state is in Postgres, so a restart or failover loses nothing.
  - Negative: a leader whose database connection stalls keeps leading until the check fails, which can take up to one check interval. SKIP LOCKED and the status guards make the overlap safe.
- Source: architecture team, implementation of PR "scan-controller core".

### D-036 Fetch and scan Jobs: launch, watch, failure mapping, GitHub App tokens
- Status: Proposed / Decided by: Architecture team / Date: 2026-10-25
- Context: The second controller PR takes a scan from FETCHING through the two Jobs of D-008 to PUBLISHING. It needs answers for open questions E-11 (podFailurePolicy action) and E-13 (which image runs source-fetch).
- Decision:
  - FETCHING, in the controller: after the DT findings, read the CycloneDX SBOM (`/api/v1/bom/cyclonedx/project/{uuid}?format=JSON&variant=inventory`), write the input bundle `work/<scanId>/<attempt>/input.json` (schema `reach.input.v1`: scan, repo, DT project, findings, SBOM), mint a GitHub App installation token, check the commit exists, then create the source-fetch Job. A scan with no findings goes straight to PUBLISHING.
  - GitHub App token: an RS256 App JWT (60s backdated `iat`, 9-minute `exp`), `GET /repos/{o}/{r}/installation`, then `POST /app/installations/{id}/access_tokens` with `permissions: {contents: read}`. The token is not limited to the one repo, so submodules in other repos of the installation stay readable (E-12). PKCS#1 keys, as GitHub issues them, are accepted. The key is `reach.github.private-key`, read from Vault KV v2 `reach/github` and re-read from config on every mint (D-032). A missing installation, repo or commit (404, 422) is `COMMIT_NOT_FOUND`; 5xx, 429, auth errors and timeouts are `GITHUB_UNAVAILABLE`.
  - source-fetch image (answers E-13): a small separate image `images/source-fetch` (UBI 9 minimal with git, tar, zstd, curl) running `fetch.sh`, not the scanner image in fetch mode. The trusted step then carries no build toolchains, and the untrusted image carries no git credentials logic. The token is passed only as an `http.https://<host>/.extraHeader`, never in a URL or `.git/config`; only the https protocol is allowed, so a hostile `.gitmodules` cannot use `file://`, ssh or `ext::`. Exit codes: 10 `COMMIT_NOT_FOUND`, only when git says the ref itself is missing; 11 `SOURCE_TOO_LARGE`; 12 `GITHUB_UNAVAILABLE`, including "Repository not found", which GitHub also answers for a token it rejects, right after the controller saw the repo; 13 `S3_UNAVAILABLE`; any other non-zero code counts as `GITHUB_UNAVAILABLE`.
  - Jobs: one per stage and attempt, named `reach-<stage>-<scanId>-<attempt>` and labelled with scan id, stage and attempt. `backoffLimit: 0`, `restartPolicy: Never`, `activeDeadlineSeconds`, `ttlSecondsAfterFinished`, requests equal to limits, no service account token, no service links, `runAsNonRoot`, `RuntimeDefault` seccomp, read-only root FS, all capabilities dropped, emptyDir with `sizeLimit` for `/work`, `/tmp` and `$HOME`, optional nodeSelector, toleration and `hostUsers: false`. Credentials and presigned URLs are files from a per-Job immutable Secret. The Job is created first and the Secret second, already owner-referenced to it, so a Secret never exists without a Job that garbage-collects it; the pod waits in ContainerCreating until the Secret appears, and if the Secret cannot be created the Job is deleted. When a fetch Job finishes (or is stale), the controller revokes its token with `DELETE /installation/token` and deletes the Secret, best effort; the token would expire within the hour anyway. A resumed or retried fetch mints a new token. Presigned URLs live for the stage deadline plus `reach.s3.job-link-margin`.
  - podFailurePolicy (answers E-11): `FailJob` on the pod condition `DisruptionTarget`. The Job fails with reason `PodFailurePolicy`, and the controller requeues the scan as `POD_DISRUPTED`, so a disruption uses up an attempt and the retry limit bounds repeated evictions. `Ignore` was rejected because it retries without limit inside the deadline and hides disruptions from the scan record.
  - Work keys: the files the Jobs exchange live under `work/<scanId>/<attempt>/` (`source.tar.zst`, `input.json`, `summary.json`). A late Job of an earlier attempt cannot overwrite what a newer attempt reads, and the untrusted scan Job never writes under the results prefix `<org>/<repo>/<commit>/<scanId>/`; the controller copies the summary there after validation (outputs PR).
  - Watching: a SharedInformer per namespace on `app.kubernetes.io/managed-by=scan-controller`, run only by the leader and stopped on lock loss. Events for one Job are handled one at a time. fetch Complete: the tarball must exist (else `S3_UNAVAILABLE`), then the scan Job is created and the scan moves to SCANNING. scan Complete: `summary.json` must exist (else `INVALID_SUMMARY`), then PUBLISHING; summary validation comes with the outputs PR. Failed: reason `DeadlineExceeded` is `DEADLINE_EXCEEDED` in either stage, `PodFailurePolicy` is `POD_DISRUPTED`, other fetch failures use the exit code. Any other scan Job failure (an engine crash or OOM) still advances to PUBLISHING when `summary.json` exists, since it may hold partial results, and is `INVALID_SUMMARY` otherwise. A Job whose scan is cancelled, finished or on a newer attempt is deleted with foreground propagation.
  - New reason code `JOB_LAUNCH_FAILED` (retryable): the Kubernetes API refused or failed to create a Job, for example a ResourceQuota on `count/jobs.batch`.
  - New reason code `INTERNAL_ERROR` (retryable): any other unexpected exception in the controller's FETCHING step (a bug, or data Postgres rejects). It requeues like other retryable codes, so `reach.retry.max-attempts` bounds a deterministic failure instead of every sweep retrying it forever. Only `StoreUnavailableException` stays unhandled, because no state can be written then; the sweep resumes those scans.
  - Reconcile on leadership: every FETCHING scan without a fetch Job for its attempt is run again (each step is idempotent); scans whose Job exists are left to the watcher. A SCANNING scan whose scan Job is gone counts as finished if `summary.json` exists, and fails with `INVALID_SUMMARY` otherwise. The SCANNING reconcile runs only once every informer has synced, and confirms a Job missing from the cache with a direct read, so an informer that failed its first list cannot fail live scans. It runs at leadership and every `reach.job.resync`.
  - Sweep (extends D-035): with Jobs on, the leader's sweep resumes FETCHING scans claimed more than `reach.queue.fetch-timeout` ago that have no fetch Job for their attempt, whether or not their DT data is stored.
  - Local runs: `reach.job.enabled=false` ends FETCHING after the token is minted, because the local stack has no cluster. Tests use the Fabric8 Kubernetes mock server in CRUD mode, adobe/s3mock and WireMock GitHub (D-034).
- Alternatives considered:
  - Scanner image in fetch mode (D-030's first proposal): rejected for the reasons above.
  - Watching Pods instead of Jobs: rejected; the Job's conditions already carry `DeadlineExceeded` and `PodFailurePolicy`, and only the fetch exit code needs a pod read.
  - Limiting the token to the one repository: rejected for now, because it breaks private submodules in the same installation (E-12).
- Consequences:
  - Positive: every outcome of both Jobs maps to one reason code, and a controller restart loses nothing.
  - Negative: PUBLISHING is a dead end until the outputs PR. A disrupted scan restarts from FETCHING, so the clone runs again.
- Source: architecture team, implementation of PR "fetch and scan Jobs".

### D-037 A scan's repository must match its DT project before write-back
- Status: Accepted / Decided by: delegated by Arik ("solve all how you see fit"), 2026-10-25 / Date: 2026-10-25
- Context: A request names a repository and a DT project separately, and nothing checked that they belong together. Any `scan-submitter`, or anything that can write to `requests.v1` (which carries no identity, 5.4a j), could pair repository A with project B. With VEX or ANALYSIS write-back, B's real vulnerabilities would be written back as `NOT_AFFECTED / CODE_NOT_REACHABLE` because A's code does not call them. A copy-paste mistake in a pipeline does the same with no attacker.
- Decision:
  - A DT project names its repositories in either of two ways, both read from the lookup response the controller already fetches (no extra DT call): a `vcs` external reference (which SBOM tools such as cyclonedx-maven fill from the SCM metadata), or a tag `reach-repo=<host>/<org>/<repo>`. URLs are reduced to lower-case `<host>/<org>/<repo>`; `scm:git:`, `git+`, scp-like `git@host:org/repo`, `.git` and sub-paths are accepted.
  - In FETCHING, right after the uuid check, the controller compares the scan's repository with the project's:
    - BOUND (the project names it): the scan proceeds unchanged.
    - MISMATCH (the project names only other repositories): the scan fails with the new permanent code `DT_REPO_MISMATCH`, in every mode except OFF. A project that says it is built from somewhere else is never scanned against this code.
    - UNBOUND (the project names none): depends on `reach.dt.repo-binding`.
  - `reach.dt.repo-binding` (scan-controller): `WRITEBACK` (default) scans an unbound project but lowers VEX or ANALYSIS write-back to COMMENT and adds a `WRITE_BACK_NARROWED` warning to the scan; `REQUIRED` fails an unbound project with the new permanent code `DT_REPO_UNBOUND`; `OFF` skips the check (for migrations only).
  - COMMENT stays allowed for unbound projects: it adds a note but changes no analysis state, so a wrong pairing is visible to the project's owners without suppressing anything.
- Alternatives considered:
  - A `repo_binding` table edited through an admin API: rejected for now. It duplicates what DT already holds, needs its own access control and drifts from DT. DT project owners are the right people to state the binding.
  - A DT project property: rejected because properties are not in the lookup response, so it costs an extra call per scan, and tags are easier to set in the DT UI and API.
  - REQUIRED as the default: rejected, because most existing projects carry no binding yet and every scan of them would fail. WRITEBACK keeps reachability results flowing while closing the suppression path; operators can switch to REQUIRED once projects are tagged.
- Consequences:
  - Positive: no scan can write NOT_AFFECTED into a project that has not named the repository. A mismatch fails fast, before any GitHub or S3 work.
  - Negative: project owners must add a `vcs` reference or a tag before VEX or ANALYSIS write-back takes effect. That DT 5's lookup response includes `externalReferences` and `tags` is UNVERIFIED (it holds for DT 4.x); if it does not, the controller needs one extra project read per scan.
- Source: post-merge review of PR #1, implemented in the review-fixes PR.

### D-038 Postgres retention: reachability-api drops whole monthly partitions
- Status: Accepted / Decided by: delegated by Arik ("solve all how you see fit"), 2026-10-25 / Date: 2026-10-25
- Context: D-025 sets 90-day Postgres retention by dropping partitions, and section 6.1 says `request_key` is pruned with them, but no component owned the job. Partitions were being created (D-033) and never removed.
- Decision:
  - A SQL function `scan.apply_retention(keep interval)` (changeset 005) does the work, as `scan.ensure_monthly_partitions` does for creation. It deletes `scan_queue` and `request_key` rows whose scan is older than `now() - keep`, then drops each monthly partition of `call_path` (once it exists), `finding` and `scan`, in that order, whose upper bound is at or before the cutoff. A partition is dropped only when its whole month is past the cutoff, so nothing younger than the retention period is lost; rows can live up to one month longer than `keep`.
  - It takes a transaction-level advisory lock, so several replicas can call it at once, and it rejects a `keep` below one day, so a mistyped setting cannot wipe the tables.
  - reachability-api runs it in its schema housekeeping on `reach.retention.cron` (default `0 45 3 * * ?`) with `reach.retention.postgres` (default P90D), next to partition creation.
- Alternatives considered:
  - pg_partman: rejected for the same reason as in D-033; one more extension to run on a managed Postgres for a few lines of SQL.
  - Running it in scan-controller: rejected, because the API already owns the schema and partition creation, and the controller only validates the schema.
  - Row-level `DELETE ... WHERE created_at < cutoff`: rejected; it bloats the tables and is what partitioning is for.
- Consequences:
  - Positive: Postgres size is bounded, and a deleted scan never leaves an idempotency key or queue row pointing at it.
  - Negative: a `metadata.uid` older than the retention period can be reused and starts a new scan. That is acceptable: emitters retry within minutes, not months.
- Source: post-merge review of PR #1, implemented in the review-fixes PR.

### D-039 Intake hardening: OIDC audience, request size limit, REQUEST_MISMATCH warning
- Status: Accepted / Decided by: delegated by Arik ("solve all how you see fit"), 2026-10-25 / Date: 2026-10-25
- Context: The post-merge review of PR #2 found that production accepted tokens minted for any client of the IdP, that REST bodies were limited only by the 10 MiB Quarkus default, and that a reused `metadata.uid` with different content silently returned the old scan.
- Decision:
  - Audience: `quarkus.oidc.token.audience` defaults to `${REACH_OIDC_AUDIENCE:reachability-api}` in every profile, and a production start (launch mode NORMAL, OIDC tenant enabled) fails when it is empty. The issuer is checked through the provider's discovery document as before; deployments that pin a key instead of using discovery must set `quarkus.oidc.token.issuer`. The integration guide now says which claim carries roles for client-credentials tokens.
  - Size: `reach.intake.max-request-bytes` (default 262144, 256 KiB) applies to both intakes before parsing. REST answers 413 `PAYLOAD_TOO_LARGE` as problem+json; Kafka sends the record to the DLQ with `reach-error-code: PAYLOAD_TOO_LARGE`. `quarkus.http.limits.max-body-size=1M` is the transport cap above it (Quarkus answers 413 with no problem body there). A valid request is a few KB, so the limit is generous.
  - REQUEST_MISMATCH: on a duplicate `metadata.uid`, the API compares the stored `unmapped.reachability` object with the new one. If they differ, the response (REST) carries a `REQUEST_MISMATCH` warning saying the new content was ignored. The warning is returned only, not stored, so the original scan's record is unchanged. On Kafka the duplicate is still accepted silently, as there is no response; the metric counts it as a duplicate.
- Alternatives considered:
  - Rejecting a mismatched duplicate with 409: rejected, because 5.4a b promises that a resubmission returns 202, and emitters retrying after a timeout must not break.
  - Comparing a hash column: rejected for now; it needs a migration and a backfill, and comparing the stored jsonb on the (rare) duplicate path is cheap.
  - A 64 KiB limit: rejected as too tight for long label sets and future fields.
- Consequences:
  - Positive: tokens for other services are rejected, oversize bodies never reach the JSON Schema validator, and emitter bugs that reuse uids become visible.
  - Negative: deployments must make their IdP put `reachability-api` (or `REACH_OIDC_AUDIENCE`) in `aud`. The wrong-audience case is not covered by an automated test yet, because the tests disable the OIDC tenant; the local profile (`token.sh`) exercises it by hand.
- Source: post-merge review of PRs #1 and #2, implemented in the review-fixes PR.

### D-040 Exfiltration through package-manager proxies: mitigate and accept the rest
- Status: Accepted / Decided by: delegated by Arik ("solve all how you see fit"), 2026-10-25 / Date: 2026-10-25
- Context: The scan Job runs untrusted build code that can reach Artifactory and S3 only (D-008, D-009). Artifactory remotes fetch whatever names the client asks for, so "only Artifactory" is not a closed channel. For example, a build script can request the Go module `attacker.example/x/@v/<base32 of source>.info`; a Go remote that resolves VCS paths, or proxy.golang.org behind it, then contacts `attacker.example`. Package names sent to npm or PyPI upstreams leak data the same way at lower bandwidth. The private source of the scanned repository is exactly what an attacker would want.
- Decision:
  - Record the risk as R16 and treat it as accepted residual risk after the mitigations below, rather than claiming the scan namespace has no egress.
  - Mitigations (operations-guide "Artifactory for the scan namespace"): the scan Job's Artifactory token can read only a dedicated virtual repository per ecosystem; those remotes point only at curated upstreams (Maven Central, the npm and PyPI registries, proxy.golang.org) and never at arbitrary VCS hosts; the Go remote resolves only through proxy.golang.org with "VCS" fetching disabled, and `GOPRIVATE`-style internal modules come from a local repository; Artifactory Curation or a block list where licensed; Artifactory request logs are shipped to the SIEM with alerts on 404 bursts and module paths outside known hosts from the scan token.
  - What stays: the public upstreams themselves still see the names requested, and a patient attacker can encode data in many small requests. This is accepted, because the alternative (no dependency resolution in the scan Job) would make reachability analysis impossible for most ecosystems.
- Alternatives considered:
  - Pre-resolving dependencies in the trusted fetch Job: rejected; it runs build tooling against the untrusted repository, which D-008 exists to avoid.
  - An offline mirror with no remote repositories: rejected for day 1 as too costly to curate; worth revisiting for high-sensitivity orgs.
- Consequences:
  - Positive: the trust statement in architecture section 2 is now accurate, and operators know what to configure in Artifactory.
  - Negative: the controls live in Artifactory, outside this repository, so they rely on the operations checklist.
- Source: post-merge review of PR #1.

### D-041 Review follow-ups: supply-chain hygiene, local stack, declined items
- Status: Accepted / Decided by: delegated by Arik ("solve all how you see fit"), 2026-10-25 / Date: 2026-10-25
- Context: The post-merge reviews of PRs #1 and #2 left several low findings. This entry records what was done and why the rest was declined.
- Decision:
  - Done:
    - CI actions are pinned to commit SHAs with the version in a comment. `.github/dependabot.yml` proposes weekly updates for GitHub Actions, Maven (Quarkus grouped, so LTS patches arrive together) and the source-fetch base image.
    - The local stack publishes every port on 127.0.0.1 only, because it uses fixed credentials and plaintext Kafka.
    - Stale lines in architecture.md: the "rotation job" for the DT key (section 11, R6) is replaced by manual rotation in Vault (D-032); the dangling "see the report list" now says the summary check is an assumption until the outputs PR implements it; `request_key` pruning names D-038.
    - The in-flight duplicate rule in section 10 ("waits for the running scan") has no mechanism yet; it is open question E-18 for the dedupe PR.
  - Declined, with reasons:
    - `findById` scans every partition when looking up a scan by id: kept. The id is the primary lookup for clients, the partitions are few (about four months kept under D-038 plus three created ahead), and each probe is a primary-key index lookup. Encoding the month in the id would change the public contract.
    - `Sql.translate` and `StoreUnavailableException` exist in both services: kept for now. The two copies already differ (the API also classifies commit-time failures, D-033), and `common` depends only on Jackson today; revisit when a third service needs them.
    - `Problem.TYPE_BASE` (`https://reach.example.internal/problems/`) stays a constant. 5.4a a already says `type` URIs are stable identifiers and clients branch on `code`; making it configurable would let two deployments emit different types for the same error.
    - The GitHub App token minted when `reach.job.enabled=false`: kept, so the local stack exercises the token path end to end against the mock GitHub.
- Alternatives considered: none beyond the items above.
- Consequences:
  - Positive: action and dependency updates are visible PRs instead of silent tag moves.
  - Negative: Dependabot PRs add review load; the Quarkus group keeps it to one PR a week for the platform.
- Source: post-merge reviews of PRs #1 and #2.

### D-042 Scan runner: input and summary contracts, module detection, verdict merge, exit codes
- Status: Accepted / Decided by: delegated by Arik ("solve all how you see fit, explain yourself in the decision files"), 2026-10-25 / Date: 2026-10-25
- Context: The scan Job (architecture.md 3.4) needs a runner that turns a source tarball and the input bundle into `summary.json`, plus fixed contracts for both files so the controller and the outputs PR can rely on them.
- Decision:
  - The runner is the `scanner` module, a Quarkus command-mode app. Engines are CDI beans of the `ReachabilityEngine` SPI (7.2) in their own `engines/*` modules; the runner orders them by `rank` and uses the first that `supports` a module.
  - Contracts: `docs/contracts/scan-input.v1.schema.json` (`reach.input.v1`) and `docs/contracts/scan-summary.v1.schema.json` (`reach.summary.v1`), validated by `ContractSchema` in `contracts`. The runner refuses an input bundle that fails the schema or names another scan. Input findings may carry `symbols[]` (from the KB, later); without them engines answer at a coarser level.
  - Module detection: the topmost `pom.xml` (else `build.gradle[.kts]` / `settings.gradle[.kts]`) per directory tree is one JVM build root, so a reactor or multi-project build is built once. Python, Go and npm roots are detected now and reported `NO_ENGINE` until their engines land. `.git`, `node_modules`, `target`, `build`, `vendor`, virtualenvs and similar are skipped; depth is capped at 12.
  - Merge: a finding analysed in several modules keeps the strongest verdict (REACHABLE > POTENTIALLY_REACHABLE > UNKNOWN > NOT_REACHABLE, 8.3); on a tie the finer level wins. `modules[]` names every module that reported it. A finding no module reported is UNKNOWN with `UNSUPPORTED_LANGUAGE` (no engine for its ecosystem) or `COMPONENT_NOT_RESOLVED` (an engine ran, but no module contains the component).
  - New finding reasons (additive under D-028 l): `SYMBOL_REACHED`, `SYMBOL_NOT_REACHED`, `COMPONENT_NOT_REFERENCED` explain a decided verdict; `ENGINE_FAILED` marks an engine crash. The existing `NO_SYMBOL_DATA`, `BUILD_FAILED`, `ENGINE_TIMEOUT`, `UNSUPPORTED_LANGUAGE` and `COMPONENT_NOT_RESOLVED` keep their meaning.
  - Budget: the runner stops `reach.scanner.reserve` (PT2M) before `reach.scanner.deadline`; modules not reached are `SKIPPED` with `ENGINE_TIMEOUT`, and the summary says `complete: false`. The controller sets the deadline to the Job's `activeDeadlineSeconds` less 3 minutes for image pull and start-up.
  - Exit codes: 0 whenever `summary.json` was uploaded, including when every module failed to build. 13 when S3 cannot be reached through the presigned links (the controller maps it to `S3_UNAVAILABLE`, retryable, like source-fetch). 1 for unusable inputs (a schema-invalid or foreign bundle, a corrupt tarball), which the controller reports as `INVALID_SUMMARY`.
  - Build diagnostics in the summary are the last 25 lines of the build log without stack frames, at most 50 per module.
- Alternatives considered:
  - One engine call per finding: rejected; a call graph per build unit serves all findings at once.
  - Failing the Job on a build failure: rejected by 3.4; a partial summary is worth more than a retry that fails the same way.
  - Building every `pom.xml` separately: rejected; modules of a reactor would be resolved against each other's unpublished artifacts and fail.
- Consequences:
  - Positive: the controller and the outputs PR get a schema-checked summary; build failures stay visible per module.
  - Negative: a repo with several unrelated Maven trees at different depths is still built per topmost tree only, so a nested tree under an unrelated parent is missed; revisit with the benchmark.
- Source: architecture.md 3.4, 7.2 and 8.3; implementation PR for the scanner.

### D-043 Java engine: SootUp CHA with lambda edges; scanner image on UBI 10
- Status: Accepted / Decided by: delegated by Arik ("solve all how you see fit, explain yourself in the decision files"), 2026-10-25 / Date: 2026-10-25
- Context: D-013 picked SootUp 3.0.1 for Java with risk R1 (Java 25 classfiles UNVERIFIED). The engine must build Maven and Gradle projects through Artifactory only (D-011) and map DT components to jars.
- Decision:
  - R1 is resolved: SootUp 3.0.1 reads Java 25 classfiles (major 69) through ASM 9.10.1; the engine's tests compile their fixtures with `--release 25`.
  - Call graph: class hierarchy analysis (CHA) from entry points, as D-013's default. SootUp's CHA does not follow `invokedynamic`, so lambdas and method references were invisible; the engine adds an edge to the `LambdaMetafactory` target method and rebuilds until no new methods appear (at most 10 rounds). JDK classes are not loaded: calls into the JDK end there, which is enough because vulnerable symbols live in dependencies.
  - Entry points: `main` methods plus every public concrete method of the application classes (7.1's day-1 default) until the phase 2 framework models.
  - Builds: Maven runs `package` plus `maven-dependency-plugin:3.8.1:build-classpath` (runtime scope) with a generated `settings.xml` whose mirror is `*`, so every repository a POM declares goes to Artifactory. Gradle runs with an init script that removes every declared repository (settings, plugin management, buildscript, project) and adds the mirror, plus a `reachClasspath` task per Java project. The image's `mvn` and `gradle` are used, never wrappers. The token reaches the build only as `REACH_ARTIFACTORY_TOKEN` in the environment (a Maven `httpHeaders` property and a Gradle header credential), never in a file in the repo. Caches live under `/work`.
  - Component matching: a DT purl `pkg:maven/<g>/<a>@<v>` matches a classpath jar by its Maven or Gradle cache path, or by `META-INF/maven/<g>/<a>/pom.properties` inside the jar (shaded copies included).
  - Verdict rules (7.1): a METHOD symbol reached is REACHABLE at SYMBOL level; a CLASS or PACKAGE symbol reached is POTENTIALLY_REACHABLE at CLASS_OR_PACKAGE; symbols given and none reached is NOT_REACHABLE. Without symbols: any class of the jar reached is POTENTIALLY_REACHABLE with `NO_SYMBOL_DATA`, otherwise NOT_REACHABLE with `COMPONENT_NOT_REFERENCED`. Up to 3 call paths per finding.
  - Scanner image: `docker.io/library/gradle:9.8.2-jdk25-ubi`, pinned by digest (UBI 10.2, Temurin 25, Gradle 9.8.2), plus zstd and Maven 3.9.16 from Maven Central checked against its SHA-512. This amends D-010's "UBI 9": the official Gradle image is the one maintained, digest-pinnable base found with JDK 25 and Gradle 9 together, and it is UBI 10. Gradle 9 is needed because Gradle 8.14 cannot run build scripts on Java 25 (tested). A UBI 9 base would mean downloading and checking Temurin and Gradle by hand. The other D-010 toolchains are added with their engines.
- Alternatives considered:
  - RTA or points-to instead of CHA: kept for later (D-013 lists them as options); CHA over-approximates, which errs towards REACHABLE.
  - Loading the JDK into the view: rejected for day 1; it multiplies graph size and memory for little gain.
  - Gradle `init.d` in the image instead of `--init-script`: equivalent; the flag keeps the script next to the engine version that needs it.
  - A UBI 9 minimal base with Temurin 25 and Gradle 9 downloaded in the Dockerfile: possible, but two more checksummed downloads to maintain for no isolation gain; revisit if UBI 10 is not allowed on the cluster.
- Consequences:
  - Positive: Java 25 is verified; lambda-heavy code no longer reports NOT_REACHABLE for reachable code.
  - Negative: CHA plus all public methods as entry points over-reports reachability; the benchmark will measure it. A base image from Docker Hub rather than the Red Hat registry; Dependabot tracks the digest.
- Source: D-013, D-011, R1; the SootUp spike in the scanner PR.

### D-044 Per-scan Artifactory token minted and revoked by the controller
- Status: Accepted / Decided by: delegated by Arik ("solve all how you see fit, explain yourself in the decision files"), 2026-10-25 / Date: 2026-10-25
- Context: D-011 calls for a short-lived read-only Artifactory token per Job. Someone has to mint it, hand it over and clean it up.
- Decision:
  - scan-controller mints the token right before it creates the scan Job: `POST /access/api/v1/tokens` with the admin token from Vault `reach/artifactory` (field `admin-token`), scope `reach.artifactory.token-scope` (default `applied-permissions/groups:reach-scan`, a group with read on the virtual repositories only), `expires_in` = `reach.artifactory.token-ttl-factor` (2.0) times the Job's deadline, not refreshable, and a transient username `reach-scan-<scanId>` so Artifactory's request log ties each download to one scan (R16, D-040).
  - The token and its id go into the scan Job's Secret (`artifactory-token`, `artifactory-token-id`); the Job gets `REACH_SCANNER_ARTIFACTORY_URL` (the platform URL plus `/artifactory`). When the Job finishes or turns stale, the watcher revokes the token (`DELETE /access/api/v1/tokens/<id>`) and deletes the Secret. If the Job cannot be created, the token is revoked at once.
  - New retryable scan reason `ARTIFACTORY_UNAVAILABLE` (additive under D-028 l): the token could not be minted. The scan stays in FETCHING and is retried like the other outages.
  - Without `reach.artifactory.url` no token is minted and the scanner builds against public registries; only the local stack does that.
  - Mock: WireMock stubs in `scan-controller/src/test/resources/wiremock/artifactory` serve the token endpoints in tests and in the local stack, and proxy `maven-virtual` to Maven Central for manual scanner runs. The scanner's own tests proxy through a WireMock Artifactory that rejects any request without the scan token.
- Alternatives considered:
  - The scanner minting its own token: rejected; untrusted code would hold the admin credential.
  - One long-lived read token shared by all scans: rejected by D-011; a leak would outlive the scan, and the request log could not tell scans apart.
  - Revoking only by expiry: kept as the fallback when the revoke fails, not as the plan; a scan usually ends long before 2x its deadline.
- Consequences:
  - Positive: a leaked token is useless after the scan; each download is attributable.
  - Negative: Artifactory becomes a hard dependency of SCANNING. The admin token can mint any token and is the most sensitive secret the controller holds; it stays in Vault and is rotated by hand like the DT key (D-032).
- Source: D-011, D-032, D-040.

## Superseded / changed decisions

Earlier draft positions that changed during the 2026-10-25 conversation:

| Earlier position | Changed to | Recorded in |
|---|---|---|
| Snyk as the commercial benchmark/engine | Snyk is a later expansion | D-022 |
| Findings taken from an SCA pod | Dependency-Track 5.x is the source of truth | D-003 |
| gVisor/Kata sandboxing assumed | Rejected: Kata unavailable, gVisor unsupported on RHCOS | D-009 |
| One Job with GitHub egress | Two-stage fetch/scan isolation | D-008 |
| Scans triggered by DT notifications | Rejected by Arik; OCSF via REST/Kafka only | D-005 |
| Request = repo@commit + DT project UUID | DT project name + version (UUID optional) | D-002 |
| Java-first language plan | Four day-1 languages | D-012 |
| Generic "one Kubernetes Job with gVisor" | restricted SCC + dedicated tainted node pool | D-009 |
| Scanner image on UBI 9 (D-010) | UBI 10 base with JDK 25 and Gradle 9 | D-043 |

## How to add a decision

- The log is append-only. Use the next free ID (D-045, D-046, ...).
- Never edit the Decision text of an Accepted decision. Supersede it with a new ID, and mark the old one "Superseded by D-0NN".
- Proposed decisions may be edited until Arik reviews them. On review, set Status to Accepted and Decided by to Arik.
- Add the new ID to the index and, if it replaces an earlier position, to the Superseded table.
