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
- Status: Accepted / Decided by: Arik / Date: 2026-10-25
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
  - Leader: a session-level `pg_try_advisory_lock(reach.leader.lock-key)` on one dedicated connection, checked every `reach.leader.check-interval`. If that connection breaks, Postgres frees the lock and the replica steps down. A new leader resumes the FETCHING scans whose DT data was never stored. Only the leader claims, publishes the outbox and purges.
  - Claim: the 6.2 query. Orgs and repos without a `concurrency_limit` row fall back to `reach.queue.org-max-running` (unset) and `reach.queue.repo-max-running` (2). A batch is also capped by the free DT workers (`reach.dt.max-concurrent`). The claim, the FETCHING update and its status event share one transaction.
  - DT read path:
    - Project lookup by name and version, then a uuid check, then paged findings that follow `X-Total-Count`. The result goes to `scan.finding` (verdict empty) with `findings_hash`.
    - The HTTP calls run outside any transaction, and every state update is guarded by the expected status.
    - 404 on lookup gives `DT_PROJECT_NOT_FOUND`; a uuid mismatch gives `DT_UUID_MISMATCH`.
    - 5xx, 429, 401/403 and connection errors are `DT_UNAVAILABLE` and retryable. A key replaced in Vault then heals on retry.
  - Retry: attempt n is requeued with `not_before = now + backoff * 2^(n-1)`, keeping its class and `enqueued_at`. When `reach.retry.max-attempts` is used up, the scan fails with the same code.
  - DT key: read from Vault KV v2 `reach/dt` through the quarkus-vault config source, and re-read from config on every DT call, so the config source's cache period controls the refresh (D-032).
  - Outbox relay: batches in id order, using each row's topic, key and headers. A row is marked published only after the broker acknowledged it. Published rows are purged after `reach.outbox.retention`.
  - Schema: the Liquibase changelog moves to the `common` module. scan-controller fails start-up while any changeset is unapplied.
  - Interim: until the Jobs PR, a scan stops in FETCHING once its DT data is loaded.
- Alternatives considered:
  - Kubernetes Lease for leader election: rejected for now, because the advisory lock needs no RBAC and is tied to the same database the work lives in.
  - Treating DT 401/403 as permanent: rejected, because a rotated key fixes itself, and the retry limit still ends the scan.
- Consequences:
  - Positive: all controller state is in Postgres, so a restart or failover loses nothing.
  - Negative: a leader whose database connection stalls keeps leading until the check fails, which can take up to one check interval. SKIP LOCKED and the status guards make the overlap safe.
- Source: architecture team, implementation of PR "scan-controller core".

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

## How to add a decision

- The log is append-only. Use the next free ID (D-036, D-037, ...).
- Never edit the Decision text of an Accepted decision. Supersede it with a new ID, and mark the old one "Superseded by D-0NN".
- Proposed decisions may be edited until Arik reviews them. On review, set Status to Accepted and Decided by to Arik.
- Add the new ID to the index and, if it replaces an earlier position, to the Superseded table.
