# Docs and contracts changelog

Contract changes (REST, Kafka topics, OCSF profile, output files) are announced here, as D-028 requires. Newest first.

## 2026-10-25: Intake implementation (reachability-api)

- `POST /api/v1/scans`, `GET /api/v1/scans/{scanId}`, `GET /api/v1/scans` and the Kafka consumer on `appsec.reachability.requests.v1` with DLQ `appsec.reachability.requests.dlq.v1` are implemented as specified in architecture.md 5.1 to 5.4a.
- Clarified within v1 (additive, D-028 l):
  - `repo.url` must be `https://<host>/<org>/<repo>` with an optional `.git`, on a host in `reach.github.allowed-hosts` (default `github.com`). Credentials, a port, a query or a fragment are rejected with 400 `SCHEMA_INVALID` (REST) or the DLQ (Kafka).
  - `link_ttl` must be positive.
  - An unknown or disabled `priority` also gives warning `PRIORITY_DOWNGRADED`.
  - New error code 500 `INTERNAL_ERROR`. 503 `UNAVAILABLE` carries `Retry-After`.
  - The `Location` header is an absolute URL.
- Config: role mapping uses the Quarkus keys `quarkus.oidc.roles.role-claim-path` (default `groups`) and `quarkus.http.auth.roles-mapping`, in place of the planned `reach.auth.roles.*-claim`. New keys: `reach.github.allowed-hosts`, `reach.kafka.topics.status`, `reach.partitions.months-ahead`, `reach.partitions.cron`.
- New decisions D-033 (intake implementation) and D-034 (mocks and local Postgres for every stage).

## 2026-10-25: Docs moved into the repository

- Docs moved from the project files into `arikregev/reachability-analyzer` (D-027). License is MIT.

## 2026-10-25: Owner review

- Accepted: D-006, D-013, D-014, D-015, D-020, D-028, D-030.
- D-029 (Flyway) is replaced by D-031 (Liquibase). D-021 is replaced by D-032 (HashiCorp Vault, static DT API key; no DT key rotation job).
- D-008 (two-stage fetch/scan) accepted after discussion.
- No contract changes.

## 2026-10-25: Initial design

- Added architecture.md, decisions.md (D-001 to D-030), integration-guide.md, operations-guide.md, faq.md, open-questions.md, README.md and CONTRIBUTING.md.
- Contracts v1 (draft until the phase 0 freeze): `contracts/scan-request.v1.schema.json`, example request and completion events, and topics `appsec.reachability.requests.v1`, `.status.v1`, `.results.v1`, `.exports.v1` and `requests.dlq.v1`.
