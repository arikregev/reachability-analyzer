# reachability-pod FAQ

Owner: Arik. Date: 2026-10-25.

> **Status: design stage. No code exists yet.** Answers describe the **planned** behavior in [architecture.md](architecture.md) and [decisions.md](decisions.md). Any procedure here is planned and untested. If this FAQ disagrees with those two, they win.

Sections: [For developers](#for-developers) | [For AppSec](#for-appsec) | [For operators](#for-operators) | [Glossary](#glossary) | [Open questions](#open-questions)

## For developers

### 1. What does reachability-pod do for me?

It takes the vulnerability findings that Dependency-Track (DT) already has for your repo. For each one, it checks whether your code can actually call the vulnerable function. You get a verdict per finding: REACHABLE, POTENTIALLY_REACHABLE, NOT_REACHABLE or UNKNOWN (D-015). That helps you fix the findings that matter first.

### 2. How do I request a scan?

Send an OCSF Scan Activity (class_uid 6007, OCSF 1.9.0) either to `POST /api/v1/scans` or to the Kafka topic `appsec.reachability.requests.v1` (D-002, D-005). Put your options in `unmapped.reachability`:
- `repo.url` and `repo.commit` (a full 40-hex SHA; branch names are rejected);
- `dt_project` name and version (uuid optional);
- optional `priority`, `formats`, `dt_write_back`, `link_ttl`.

REST needs the scan-submitter role (D-024). The example request is in architecture.md section 5.1. `metadata.uid` is the idempotency key: send the same one twice and you get the same scanId.

### 3. Which languages are supported?

Function-level on day 1 (D-012, D-013):

| Language | Build | Engine |
|---|---|---|
| Java | Maven, Gradle | SootUp (fallback OPAL) |
| Python | pip, uv, Poetry | in-house PyCG fork (fallback Joern pysrc2cpg, then import scan) |
| Go | Go modules | govulncheck |
| JS/TS | npm, pnpm, Yarn | Jelly (fallback import-level) |

Other languages are accepted. They get import-level verdicts or UNKNOWN until an engine is added. C#, Rust and Snyk are planned for phase 3.

### 4. Why no C/C++?

Day-1 scope is Java, Python, Go and JS/TS (D-012). No C/C++ engine was chosen, and the phase 3 expansions are C#, Rust and Snyk. So C/C++ repos are accepted, but every finding is UNKNOWN with reason UNSUPPORTED_LANGUAGE. The decision log records no further rationale; adding C/C++ would need a new decision.

### 5. How long does a scan take?

The design assumes a median of about 8 minutes (D-020). That is an estimate, to be measured in phase 0 and phase 3. Hard limits (proposed defaults):
- source fetch: 10 minutes (`reach.job.fetch.active-deadline`);
- scan: 45 minutes (`reach.job.scan.active-deadline`).

Queue wait comes on top. It depends on your priority class and on load. A duplicate request (same repo, commit and DT findings within one day) completes at once and reuses the earlier results (`reused_from`).

### 6. What happens with monorepos and submodules?

One GitHub repo maps to one DT project with one merged SBOM (D-019). Submodules are cloned recursively. The scan Job finds every module per ecosystem (pom.xml, build.gradle(.kts), pyproject.toml/requirements, go.mod, package.json workspaces), including inside submodules. Each finding is mapped to modules through the SBOM dependency graph. If a finding is in several modules, the strongest verdict wins (REACHABLE > POTENTIALLY_REACHABLE > UNKNOWN > NOT_REACHABLE). If it maps to no module, it is UNKNOWN / COMPONENT_NOT_RESOLVED (proposed code).

The GitHub App must be installed on submodule repos too.

### 7. My build uses a private registry, or a custom `.npmrc`. Will it work?

All dependencies come from Artifactory only (D-011). The service injects registry config without touching your repo. If your repo forces another registry (for example in `.npmrc`), the build cannot reach it, because the scan Job can reach only Artifactory and S3. That module then fails with BUILD_FAILED and its findings become UNKNOWN. Make sure your private packages are available through Artifactory.

### 8. My project needs a specific JDK, Python, Go or Node version. What happens?

The scan Job uses one polyglot image with many toolchains (D-010). It does not download toolchains at run time. If the version you need is not in the image, that module fails with BUILD_FAILED. Ask the platform team to add it to the image manifest. The image is rebuilt weekly.

### 9. Does it upload my code anywhere?

Your code goes only to places inside the service's boundary:
1. A trusted source-fetch Job clones the commit from GitHub and uploads a tarball to **our own S3 bucket**.
2. The scan Job downloads it from that bucket, builds it and analyses it in an isolated namespace.

The scan Job can reach only Artifactory (for dependencies) and our S3 bucket. It cannot reach GitHub or the internet (D-008, D-009). Results stay in our S3 bucket and Postgres. No third-party service gets your code. The Snyk engine (phase 3) would need a fresh review on this point.

## For AppSec

### 10. Does NOT_REACHABLE mean safe?

No. It means: with the data and analysis we have, we found no call path from your code to the vulnerable code. Read it as "lower priority", not "safe". Check two things:
- **`level`**. SYMBOL is the strongest. CLASS_OR_PACKAGE and IMPORT are coarse. An IMPORT-level NOT_REACHABLE only means the package is not imported.
- **Known blind spots.** Reflection, dependency injection and lambdas in Java (entry-point models come in phase 2); `getattr`, `importlib`, decorators and C extensions in Python; dynamic `require`/`eval` in JS. These can hide real calls, so Python NOT_REACHABLE has lower confidence (D-013).

On day 1, Java and Python treat all public methods and module-level code as entry points (R13). That is conservative: it gives fewer NOT_REACHABLE verdicts, not more.

### 11. Why is my finding UNKNOWN?

UNKNOWN always comes with a reason code (D-015):

| Reason | Meaning | What to do |
|---|---|---|
| BUILD_FAILED | the module did not build or install in the scan Job | check the registry and toolchain (questions 7 and 8) |
| ENGINE_TIMEOUT | the engine ran out of time | large module; ask operators about timeouts |
| NO_SYMBOL_DATA | the knowledge base has no vulnerable-symbol data for this vulnerability | ask for curation (question 15) |
| UNSUPPORTED_LANGUAGE | no engine for this language (for example C/C++) | expected |
| COMPONENT_NOT_RESOLVED (proposed) | the finding's purl was not found in any module | check the SBOM in DT is complete |

If some findings are UNKNOWN because of BUILD_FAILED or ENGINE_TIMEOUT, the whole scan ends PARTIAL.

### 12. What is the difference between REACHABLE and POTENTIALLY_REACHABLE?

REACHABLE means a call path to the vulnerable symbol was found. You get the call path (symbol, file, line). POTENTIALLY_REACHABLE means we have weaker evidence: only class- or package-level data, a fallback engine, an import-level scan, or an engine that timed out with partial results. Treat it like REACHABLE until someone looks.

### 13. Where do the results go, and in what formats?

To S3 under `s3://<bucket>/<org>/<repo>/<commit>/<scanId>/` (D-016):
- `ocsf.json`: OCSF 2002 Vulnerability Finding per DT finding;
- `cyclonedx-vex.json`: CycloneDX 1.6 VEX;
- `openvex.json`: OpenVEX;
- `summary.json`: always written; modules, toolchains, verdicts, call paths, image digest.

When the scan finishes, a Completed event with presigned links goes to `appsec.reachability.results.v1` (D-017). Links last 24h by default. Re-sign them any time with `POST /api/v1/scans/{scanId}/links`. Results are kept 1 year in S3 and 90 days in Postgres by default (D-025).

### 14. Can I use it without Dependency-Track?

No. DT 5.x is the source of truth for findings (D-003). Every request names a DT project (name + version), and the service reads that project's findings and SBOM. The only triggers are OCSF requests by REST or Kafka; the service does not listen to DT notifications (D-005). A DT-to-OCSF adapter could be built later, outside this service, but it would still use DT.

### 15. Will it change my analyses in Dependency-Track?

Not by default. DT write-back is OFF (D-004). Sys-admins can turn it on per org or repo:
- COMMENT: adds details and a comment; the analysis state is untouched.
- VEX: uploads CycloneDX VEX to DT.
- ANALYSIS: sets NOT_AFFECTED + CODE_NOT_REACHABLE for NOT_REACHABLE verdicts.

A request can only narrow the policy, not widen it. A proposed guard skips analyses that someone else already set beyond NOT_SET or IN_TRIAGE. DT vuln policies might overwrite our write-back (UNVERIFIED, R7).

### 16. How do I get results for many projects at once, for a dashboard?

Use `POST /api/v1/results/query` (role results-reader) (D-018). Filter by DT project name + version (primary), repo, vuln ID (CVE/GHSA/OSV, alias-matched), purl, verdict and time. It returns pages up to `reach.results.sync-max-rows` (10,000, proposed). Bigger queries return 422 `EXPORT_REQUIRED`; use `POST /api/v1/results/exports` for an async NDJSON or OCSF export to S3. Dashboards and per-team views are out of scope for this service (D-026).

### 17. Snyk?

Snyk is a later expansion, planned for phase 3 (D-022). It would plug in as another engine behind the same `ReachabilityEngine` SPI, using `snyk test --reachability`. That Snyk feature is in Preview, so its status will be rechecked at phase 3. Day 1 has no commercial dependency.

### 18. A new CVE was published. Will my repo be re-scanned?

Not by itself. The service does not consume DT notifications (D-005). An emitter (CI or any service) must send a new request. Each scan reads the current findings from DT at that time.

## For operators

### 19. How is untrusted code isolated without Kata?

Kata is not available and gVisor is unsupported on RHCOS (D-009). The design layers supported OpenShift controls instead:
- two stages: a trusted fetch Job with GitHub access, then an untrusted scan Job without it (D-008);
- restricted-v2 SCC, and restricted-v3 with `hostUsers: false` on OCP 4.20+;
- a dedicated tainted scanner MachineSet;
- default-deny NetworkPolicy plus EgressFirewall: Artifactory and S3 only;
- S3 VPC endpoint policy pinned to our bucket;
- no ServiceAccount token, no RBAC, read-only root FS, storage limits;
- one fresh Job per scan, never reused.

Details: [operations-guide.md](operations-guide.md) section 3.

### 20. How many scans can it handle?

The target is 10,000 scans per day, about 7 per minute (D-020). Estimated peak: about 170 concurrent scans and 680 vCPU. These are estimates to be measured. Bursts wait in the Postgres queue. Sys-admins tune priority classes and caps at run time without redeploys (D-007).

### 21. How do priorities work?

Each request names a priority class: critical, pr, default or bulk (proposed weights 1000, 500, 100, 10). Unknown or not-permitted classes fall back to `default`. Waiting requests gain weight over time (aging), so low classes never starve. Caps apply at global, org, repo and class level (D-007). Sys-admins edit them with `/api/v1/admin/priority-classes` and `/api/v1/admin/concurrency-limits`.

### 22. Can I cancel a scan?

Yes, while it is QUEUED, FETCHING or SCANNING: `POST /api/v1/scans/{scanId}/cancel` (role scan-submitter). The Job is deleted and a Cancelled event goes to `appsec.reachability.status.v1`. In PUBLISHING the cancel is rejected with 409, because publishing is short and idempotent.

### 23. Is it multi-tenant? Does it have a UI?

Single tenant. The only UI is a sys-admin console inside reachability-api (Qute + htmx): queue view, priority policies, concurrency caps, write-back config and KB overrides (D-026).

### 24. Why might results change between two scans of the same commit?

Three inputs can change:
- **DT findings**: new or retracted vulnerabilities change the finding set.
- **Knowledge base**: new symbol data can move a verdict from CLASS_OR_PACKAGE to SYMBOL level.
- **Scanner image**: rebuilt weekly. Each scan records `image_digest`, so you can compare.

## Glossary

| Term | Meaning |
|---|---|
| **Reachability** | Whether code in your application can actually execute a vulnerable function in a dependency. |
| **Verdict** | Our answer per finding: REACHABLE, POTENTIALLY_REACHABLE, NOT_REACHABLE or UNKNOWN (D-015). |
| **Reason code** | Why a verdict is UNKNOWN or weakened: NO_SYMBOL_DATA, BUILD_FAILED, ENGINE_TIMEOUT, UNSUPPORTED_LANGUAGE, COMPONENT_NOT_RESOLVED (proposed). |
| **Level** | How fine-grained the evidence is: SYMBOL, CLASS_OR_PACKAGE or IMPORT (D-014). |
| **Call graph** | A graph of which functions can call which. Engines search it from entry points to vulnerable symbols. |
| **Call path** | One chain of calls from an entry point to the vulnerable symbol. Reported as frames (symbol, file, line). |
| **Entry point** | Where analysis starts: for example `main`, public methods, or module-level code. On day 1, Java uses `main` plus all public methods of application classes; Python uses all module-level code and functions. |
| **CHA** | Class Hierarchy Analysis. A fast call-graph method that assumes a virtual call can reach any override in the class hierarchy. Over-approximates. The Java default (D-013). |
| **RTA** | Rapid Type Analysis. Like CHA, but only counts classes that are actually instantiated. More precise, a bit slower. |
| **Points-to analysis (Qilin)** | A more precise and more costly analysis of what objects a variable can refer to. Optional for Java. |
| **Fallback engine** | The next engine by `rank()` that runs when the primary cannot. Its verdicts are at most CLASS_OR_PACKAGE level. |
| **Knowledge base (KB)** | The Postgres `kb` schema: which functions or classes are vulnerable for each vulnerability (D-014). |
| **Vulnerable symbol** | A function, method, class or package named as the vulnerable code for one vulnerability. |
| **Override** | A manual KB change: ADD_SYMBOL, REMOVE_SYMBOL or FORCE_VERDICT. Applied last. |
| **Fix commit** | The commit that fixed a vulnerability. The methods it changed are a source of vulnerable symbols. |
| **SBOM** | Software Bill of Materials: a list of components and their dependency graph. DT holds one merged SBOM per project (D-019). |
| **purl** | Package URL, a standard ID for a package, for example `pkg:maven/group/artifact@version`. Used to match findings to modules. |
| **Finding** | One (component, vulnerability) pair in DT. Its identity is (componentUuid, vulnerabilityUuid); DT has no finding UUID. |
| **Dependency-Track (DT)** | The OWASP SBOM and vulnerability platform. Version 5.x is our source of findings (D-003). |
| **VEX** | Vulnerability Exploitability eXchange: a statement of whether a product is affected by a vulnerability. We produce CycloneDX 1.6 VEX and OpenVEX. |
| **OpenVEX** | A small, standalone VEX format. |
| **CycloneDX** | An SBOM and VEX standard. We output version 1.6 VEX. |
| **OCSF** | Open Cybersecurity Schema Framework, a common event schema. We take Scan Activity (class 6007) as input and emit Vulnerability Finding (class 2002) as output. |
| **`unmapped.reachability`** | Our own block inside OCSF events, validated by a versioned JSON Schema (D-023). |
| **Presigned URL** | A time-limited S3 link that works without AWS credentials. Default life 24h (D-017). |
| **Dedupe** | Reusing an earlier scan for the same repo, commit and DT findings hash within `reach.dedupe.window` (D-020). |
| **Outbox** | A Postgres table of events to publish. It is written in the same transaction as the state change, then relayed to Kafka. Gives at-least-once delivery. |
| **DLQ** | Dead-letter queue: `appsec.reachability.requests.dlq.v1`, where schema-invalid requests go. |
| **Polyglot image** | The one scanner container image with all toolchains and engines (D-010). |
| **SCC** | OpenShift Security Context Constraints. restricted-v2 and restricted-v3 limit what pods may do. |
| **EgressFirewall** | An OpenShift OVN object that allows or denies outbound traffic by CIDR or DNS name, per namespace. |
| **MachineSet** | An OpenShift object that manages a group of nodes. The scanner pool has its own tainted MachineSet. |
| **SPI** | Service Provider Interface. `ReachabilityEngine` is the SPI every engine implements. |
| **PARTIAL** | A scan that finished but has some UNKNOWN verdicts from BUILD_FAILED or ENGINE_TIMEOUT. |

## Open questions

1. Typical scan time per ecosystem is not measured yet; the 8-minute median is an assumption (D-020).
2. How long source tarballs stay in S3 is not stated separately from results.
3. Whether the Snyk engine sends code or metadata to Snyk's service, and if that is acceptable, is not decided.
4. The list of toolchain versions in the polyglot image is not published yet.
