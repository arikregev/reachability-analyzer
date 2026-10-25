# Contributing to reachability-pod

Owner: Arik. Date: 2026-10-25.

> **Status: design stage. No code exists yet.** The repo layout, setup and workflows below are **proposed and planned**. They follow [docs/architecture.md](docs/architecture.md) and [docs/decisions.md](docs/decisions.md). If this file disagrees with those two, they win. Gaps are listed in [Open questions](#11-open-questions).

## Contents

1. [Before you start](#1-before-you-start)
2. [Repo layout (proposed)](#2-repo-layout-proposed)
3. [Dev setup (planned)](#3-dev-setup-planned)
4. [Coding conventions (proposed)](#4-coding-conventions-proposed)
5. [How to add a language engine (planned)](#5-how-to-add-a-language-engine-planned)
6. [How to add or curate vulnerable symbols (planned)](#6-how-to-add-or-curate-vulnerable-symbols-planned)
7. [Benchmark and quality gate (planned)](#7-benchmark-and-quality-gate-planned)
8. [Decision process](#8-decision-process)
9. [Pull request checklist](#9-pull-request-checklist)
10. [Security reporting](#10-security-reporting)
11. [Open questions](#11-open-questions)

## 1. Before you start

Read these first:
- [docs/architecture.md](docs/architecture.md): components, contracts, data model, engines, security.
- [docs/decisions.md](docs/decisions.md): every decision, with IDs D-001 to D-027.
- [docs/faq.md](docs/faq.md): short answers to common questions.
- [docs/operations-guide.md](docs/operations-guide.md): how it runs on OpenShift.

Three rules shape most code reviews:
1. **Untrusted code stays in `reach-scan`.** Nothing that runs there gets GitHub, DT, Kubernetes or AWS credentials (D-008, D-009).
2. **Failures are explicit.** A failed build or engine gives UNKNOWN with a reason code, never a silent NOT_REACHABLE (D-015).
3. **Coarse verdicts are labelled.** Every verdict carries a `level`: SYMBOL, CLASS_OR_PACKAGE or IMPORT (D-014).

## 2. Repo layout (proposed)

This layout is a proposal. The repo does not exist yet (D-027).

```
reachability-pod/
  reachability-api/      Quarkus service: REST + Kafka intake, status, bulk query,
                         exports, admin API, sys-admin UI (Qute + htmx)
  scan-controller/       Quarkus service: queue claim, DT fetch, dedupe, KB lookup,
                         Job launch/watch, result indexing, rendering, write-back, outbox
  scanner/               scan Job runner: module detection, builds, engine orchestration,
                         summary.json; also the polyglot image manifest (D-010)
  engines/
    sootup/              Java, primary (rank first)
    opal/                Java, fallback
    pycg/                Python, primary (pinned in-house fork)
    joern-py/            Python, fallback
    govulncheck/         Go
    jelly/               JS/TS
    import-scan/         last-resort import-level scan
  symbol-kb-builder/     CronJob: fills the kb schema, refreshes the Go vulndb zip
  contracts/             JSON Schemas: unmapped.reachability request block,
                         summary.json, Kafka event payloads
  deploy/                OpenShift manifests: namespaces, SCC bindings, NetworkPolicy,
                         EgressFirewall, quotas, MachineSet, CronJobs
  docs/                  architecture.md, decisions.md, operations-guide.md, faq.md
  CONTRIBUTING.md
```

Engine directory names follow the engine `id()` values in architecture.md section 7.2: `sootup`, `opal`, `pycg`, `joern-py`, `govulncheck`, `jelly`, `import-scan`.

## 3. Dev setup (planned)

### 3.1 Tools

- **Java 25** (D-001).
- **Quarkus 3.40 LTS** (D-001). The Quarkus CLI is handy but optional.
- A container runtime (Docker or Podman) for Testcontainers and Quarkus Dev Services.
- For engine work: the toolchains of that ecosystem (Maven/Gradle, Python with pip/uv/Poetry, Go, Node 22). The polyglot image has all of them (D-010).

### 3.2 Run a service in dev mode

```bash
# planned; from the module directory, e.g. reachability-api/
quarkus dev
```

Quarkus Dev Services start what the service needs:
- **Postgres 18** for the `scan` and `kb` schemas.
- **Apache Kafka** for the `appsec.reachability.*` topics.

You do not need a local DT, GitHub, Artifactory or S3 for most work. Use test doubles at those boundaries (see 3.3).

OIDC in dev mode: use the Quarkus OIDC Dev Services or a test token. The three roles are scan-submitter, results-reader and sys-admin (D-024).

### 3.3 Tests

- **Unit tests** for verdict mapping, the claim query rules, reason codes and format rendering.
- **Integration tests** with Testcontainers (Postgres 18, Kafka). Test the claim query (`FOR UPDATE SKIP LOCKED`), outbox relay, idempotency on `metadata.uid`, and DLQ routing.
- **Contract tests**: every sample in `contracts/` validates against its JSON Schema. Rendering tests check the OCSF 2002, CycloneDX 1.6 VEX and OpenVEX outputs against the mapping in architecture.md section 5.6.
- **Engine tests**: small fixture projects per ecosystem with known reachable and not-reachable cases. These feed the benchmark (section 7).
- **External systems** (DT, GitHub, Artifactory, S3): stub at the HTTP boundary. Recorded DT responses should follow the shapes in architecture.md section 8.1.

## 4. Coding conventions (proposed)

These are proposals drawn from the architecture. Confirm or replace them with a decision (section 8).

- **Language**: Java 25 and Quarkus for all services (D-001). Use records for data carriers, as the SPI does.
- **Names from the design**: use the exact names in architecture.md for components, topics, tables, verdicts, reason codes and config keys. Do not invent synonyms.
- **Config**: all keys live under `reach.*` (architecture.md section 12). Every new key needs a default, and the docs table must list it.
- **Metrics**: names start with `reach_` and use labels like the existing ones (section 11 of architecture.md).
- **Logs**: JSON. Never log tokens, keys or presigned URLs. Mount secrets as files, not env vars.
- **Tracing**: propagate `traceparent` through Kafka headers, the outbox and Job env.
- **Contracts**: any change to a request, event or `summary.json` shape changes a schema in `contracts/` and follows versioning (section 4.1).
- **Database**: scan tables are partitioned monthly by `created_at` (D-025). New scan tables follow the same rule. Migrations are backward-compatible (expand, then contract).
- **Idempotency**: Kafka consumers dedupe on scan uid. Publishing is at-least-once through the outbox.
- **Licences**: check the licence of every new engine or library. AGPL is a no (PyXray was rejected for it, D-013). LGPL needs legal review (R2).

### 4.1 Contract versioning

- Kafka topics end in `.v1`. A breaking change means a new topic version.
- The request block has `schema_version` ("1"). A breaking change bumps it, and the service accepts both versions during migration.
- `summary.json` is written by the untrusted scan Job and validated by the controller. The controller must accept a new version before any image produces it.

## 5. How to add a language engine (planned)

Day-1 engines cover Java, Python, Go and JS/TS (D-012, D-013). Planned expansions are C#, Rust and Snyk (phase 3, D-022). C/C++ stays UNKNOWN / UNSUPPORTED_LANGUAGE (D-012).

### 5.1 Record the decision first

Open a Proposed decision in `docs/decisions.md` (next free ID, D-028 or later). Cover the engine, its licence, inputs, limits and fallback. See section 8.

### 5.2 Implement the SPI

Implement `ReachabilityEngine` exactly as sketched in architecture.md section 7.2:

```java
public interface ReachabilityEngine {
  String id();                              // "sootup", "opal", "pycg", "joern-py", "govulncheck", "jelly", "import-scan"
  String version();
  Set<Ecosystem> ecosystems();
  int rank();                               // lower runs first; next rank is the fallback
  boolean supports(ModuleDescriptor module);
  PreparedModule prepare(ModuleDescriptor module, BuildEnv env) throws BuildException;
  EngineResult analyse(PreparedModule module, List<TargetFinding> targets, Duration budget);
}
record TargetFinding(UUID componentUuid, UUID vulnerabilityUuid, String purl, String vulnId,
                     List<String> aliases, List<VulnerableSymbol> symbols) {}
record EngineResult(List<FindingVerdict> verdicts, boolean complete, List<String> diagnostics) {}
record FindingVerdict(TargetFinding target, Verdict verdict, ReasonCode reason, Level level,
                      List<CallPath> paths) {}
```

What each method must do:

| Method | Rule |
|---|---|
| `id()` | short, stable, lowercase. It appears in `summary.json`, OCSF `unmapped.reachability.engine`, metrics labels and config keys `reach.engine.<id>.*`. |
| `version()` | the exact pinned engine version. It is recorded per finding as `engine_version`. |
| `ecosystems()` | the ecosystems this engine handles. |
| `rank()` | lower runs first. If your engine is a fallback, give it a higher rank than the primary. |
| `supports()` | cheap check on the module (build file, language). No network, no build. |
| `prepare()` | the build/install step (5.3). Throw `BuildException` on failure; the runner turns that into UNKNOWN / BUILD_FAILED for the module. |
| `analyse()` | run the engine within `budget`. Return `complete=false` if you stopped early. Put engine messages in `diagnostics`. |

Engines are discovered via CDI in the scan runner. Make your class a CDI bean.

Add config keys `reach.engine.<id>.enabled` (default true) and `reach.engine.<id>.timeout` (default 50% of the scan deadline per module, proposed).

### 5.3 Write the build/install step

The scan Job runs in `reach-scan`. That shapes the build step:
- **Artifactory only.** Inject registry config through env vars, settings files or init scripts. Never edit the repo. Use the image's tools, not wrappers (`mvn`, not `mvnw`) (D-011). Add your package manager's injection to architecture.md section 9.
- **No runtime downloads** of toolchains. A version not in the image fails the module with BUILD_FAILED. Add the toolchain to the polyglot image manifest instead (D-010).
- **Arbitrary UID, read-only root FS.** Write only to `/work`, `/tmp` and `$HOME` (emptyDir).
- **No credentials** beyond the per-job Artifactory token and presigned URLs.
- **Stay inside the budget.** The runner stops engines before `activeDeadlineSeconds` and writes a partial summary.
- Per-module failures must not fail the Job. Only infrastructure failures do.

### 5.4 Map the output to verdicts and reason codes

Use only these verdicts (D-015): REACHABLE, POTENTIALLY_REACHABLE, NOT_REACHABLE, UNKNOWN.

Use existing reason codes where they fit: NO_SYMBOL_DATA, BUILD_FAILED, ENGINE_TIMEOUT, UNSUPPORTED_LANGUAGE, and COMPONENT_NOT_RESOLVED (proposed). A new reason code needs a decision.

Set `level` honestly (architecture.md section 7.3):

| Level | When | Verdicts possible |
|---|---|---|
| SYMBOL | KB has function/method symbols and the primary engine completed | all four |
| CLASS_OR_PACKAGE | only class or package data, or a fallback engine | REACHABLE is downgraded to POTENTIALLY_REACHABLE; NOT_REACHABLE only if the package is unreferenced |
| IMPORT | last resort, import AST scan | POTENTIALLY_REACHABLE (imported) or NOT_REACHABLE (not imported) |
| none | unsupported language, build failed | UNKNOWN + reason |

Rules that every engine follows:
- Do not return NOT_REACHABLE when the analysis did not complete. A timeout gives POTENTIALLY_REACHABLE with ENGINE_TIMEOUT (as Jelly does) or UNKNOWN with a reason.
- Return call paths for REACHABLE verdicts: frames with symbol, file and line.
- Map engine package names to the DT purl. Look at the Java shaded-class mapping via `pom.properties` and the Go join on OSV aliases for examples.
- The runner combines modules. A finding that resolves in several modules takes the strongest verdict: REACHABLE > POTENTIALLY_REACHABLE > UNKNOWN > NOT_REACHABLE.

Write the verdict rules for your engine as one column of the table in architecture.md section 7.1.

### 5.5 Add benchmark cases

Add labelled fixture projects for your ecosystem to the benchmark (section 7). Include at least:
- a vulnerable symbol that is called (expect REACHABLE);
- a vulnerable package imported but the symbol not called;
- a vulnerable package present but not imported;
- a case your engine cannot see (dynamic dispatch, reflection or similar), labelled with the true answer;
- a build failure, to check UNKNOWN / BUILD_FAILED.

### 5.6 Update docs and decisions

- `docs/architecture.md`: section 7.1 table (engine, version, licence, inputs, build, command, parsing, verdict rules, limitations), section 9 Artifactory injection if new, section 12 config keys, section 13 risks.
- `docs/decisions.md`: the decision from 5.1, and its index row.
- Polyglot image manifest: the engine and toolchain.
- `docs/faq.md`: the supported language list.

## 6. How to add or curate vulnerable symbols (planned)

Function-level verdicts need to know which functions are vulnerable. That data lives in the Postgres `kb` schema (D-014).

### 6.1 Where symbols come from

In precedence order:
1. Go OSV `imports.symbols`.
2. GHSA `vulnerable_functions` (sparse).
3. Fix-commit method diffs from OSV FIX refs and SAP Project KB (Java, Python).
4. Manual curation via overrides.

Eclipse Steady data is stale (2022) and is not used.

symbol-kb-builder fills sources 1-3 automatically. Manual curation is how people improve the KB.

### 6.2 Data model

| Table | What it holds |
|---|---|
| `kb.vulnerability` | vuln_id, aliases, ecosystem, source, modified_at |
| `kb.fix_commit` | vuln_id, repo_url, commit_sha, source (OSV_FIX, SAP_KB) |
| `kb.vulnerable_symbol` | vuln_id, package (versionless purl), symbol (FQN), kind (FUNCTION, METHOD, CLASS, PACKAGE), source (OSV_GO, GHSA, FIX_DIFF, MANUAL) |
| `kb.symbol_version` | symbol_id, introduced, fixed, last_affected |
| `kb.override` | vuln_id, package, action (ADD_SYMBOL, REMOVE_SYMBOL, FORCE_VERDICT), symbol, verdict, author, reason, created_at |

Lookup is by (package, vulnId). DT `aliases[]` are tried in order. Overrides apply last.

### 6.3 Curate with overrides

Use the admin API (role sys-admin) or the sys-admin console:
- `GET /api/v1/admin/kb/overrides`
- `POST /api/v1/admin/kb/overrides`
- `DELETE /api/v1/admin/kb/overrides`

Illustrative request (fields from the `kb.override` table; the exact body is not defined yet):

```json
{
  "vuln_id": "GHSA-xxxx-xxxx-xxxx",
  "package": "pkg:maven/org.example/example-lib",
  "action": "ADD_SYMBOL",
  "symbol": "org.example.lib.Parser.parse",
  "reason": "Method changed in fix commit <sha>; see advisory <url>"
}
```

Actions:
- **ADD_SYMBOL**: add a vulnerable symbol the sources missed.
- **REMOVE_SYMBOL**: remove a wrong symbol (for example, a refactor in the fix diff that is not the vulnerable code).
- **FORCE_VERDICT**: set a verdict for a (package, vuln) pair. Use rarely. Always give a reason.

### 6.4 Curation checklist

- [ ] The `package` is a versionless purl that matches the DT component purl.
- [ ] The `symbol` is a fully qualified name in the form your engine reports.
- [ ] The `reason` links the evidence: advisory, fix commit or analysis.
- [ ] The vuln ID is the one in the KB, or one of its aliases.
- [ ] For a new symbol, a benchmark case shows it gives the expected verdict.
- [ ] FORCE_VERDICT has a second reviewer.

## 7. Benchmark and quality gate (planned)

### 7.1 The benchmark

A labelled set of real or fixture projects, built in phase 0 (architecture.md section 14). Each case is a (project, commit, finding) with a human label: reachable or not reachable.

The benchmark runs:
- on every engine change;
- on every polyglot image rebuild (weekly);
- on large KB changes.

### 7.2 Metrics

Report per ecosystem, per engine and per `level`:

| Metric | Meaning |
|---|---|
| **Precision** | of the findings we call reachable, how many are truly reachable |
| **Recall** | of the truly reachable findings, how many we call reachable. Low recall means false NOT_REACHABLE, the most harmful error. |
| **UNKNOWN rate** | share of findings with verdict UNKNOWN, by reason code |

### 7.3 The gate

- Phase 0 exit: precision and recall baselines are agreed with Arik.
- Phase 2 exit: the benchmark meets the agreed targets.
- A change that lowers recall or raises the UNKNOWN rate against the baseline does not merge without a written reason in the PR.

The numeric targets are not set yet (see Open questions).

## 8. Decision process

Decisions live in [docs/decisions.md](docs/decisions.md). The log is **append-only**.

- Use the next free ID: D-033, D-034, and so on.
- Each entry has Status, Decided by, Date, Context, Decision, Alternatives considered, Consequences and Source.
- New decisions from contributors start as **Proposed**. Arik reviews them. On review, Status becomes Accepted and Decided by becomes Arik.
- Proposed decisions may be edited until Arik reviews them.
- Never edit the Decision text of an Accepted decision. Supersede it with a new ID and mark the old one "Superseded by D-0NN".
- Add every new ID to the index. If it replaces an earlier position, add it to the Superseded table.

You need a decision for: a new engine or language, a new verdict or reason code, a contract change, a new external dependency, any change to the isolation model, and any change to defaults set by a decision.

## 9. Pull request checklist

- [ ] Names (components, topics, tables, keys, verdicts) match architecture.md exactly.
- [ ] Decision IDs cited where the change applies one. New decisions added as Proposed.
- [ ] Tests added: unit, and integration with Testcontainers where Postgres or Kafka is involved.
- [ ] Contract changes: schema in `contracts/` updated and versioned; samples validate.
- [ ] Engine changes: benchmark run; precision, recall and UNKNOWN rate in the PR description.
- [ ] No secrets, tokens or presigned URLs in code, tests, fixtures or logs.
- [ ] Nothing new runs in `reach-scan` with credentials or new egress.
- [ ] New config keys have defaults and are listed in architecture.md section 12.
- [ ] New metrics follow the `reach_` naming and are listed in architecture.md section 11.
- [ ] DB changes are backward-compatible (expand, then contract) and respect monthly partitioning.
- [ ] Licence of any new dependency checked.
- [ ] Docs updated: architecture.md, operations-guide.md, faq.md as needed.

## 10. Security reporting

Do not open a public issue for a security problem.

Report privately to the project owner, Arik. The private reporting channel is not set up yet (see Open questions).

Include:
- what you found and where;
- how to reproduce it;
- the impact, for example an escape from `reach-scan`, a way to reach GitHub or another S3 bucket from a scan Job, a credential in a log, or a forged `summary.json` accepted by the controller.

Areas of special interest: the isolation of `reach-scan` (D-008, D-009), validation of `summary.json`, per-job Secrets, presigned URL scope, and DT write-back.

## 11. Open questions

1. Resolved: Maven multi-module (D-030).
2. Which module holds the source-fetch Job code and image.
3. Numeric targets for precision, recall and UNKNOWN rate, and how POTENTIALLY_REACHABLE counts in precision and recall.
4. Where the benchmark corpus lives and who labels it.
5. The exact request body and validation rules of `/api/v1/admin/kb/overrides`, and the FQN format per ecosystem.
6. Formatter, static analysis and code style tools.
7. Private security reporting channel (email or GitHub private advisories).
8. Whether a second reviewer is required for FORCE_VERDICT overrides (proposed above) or any other change.
9. Resolved: Liquibase (D-031).
