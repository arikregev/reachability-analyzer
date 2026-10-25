# Docs and contracts changelog

Contract changes (REST, Kafka topics, OCSF profile, output files) are announced here, as D-028 requires. Newest first.

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
