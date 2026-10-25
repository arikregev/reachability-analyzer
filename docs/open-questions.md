# Open questions

This is the single list of what is still undecided. Each document keeps its own "Open questions" section with full context; this page collects them so they can be closed in one pass. When a question is answered, record it in [decisions.md](decisions.md) as a new decision, then remove it here.

Last updated: 2026-10-25 (after Arik's review of the Proposed decisions).

## Needs the owner (Arik)

| # | Question | Where it matters |
|---|---|---|
| O-1 | Should D-032 extend Vault to all long-lived secrets (GitHub App key, IAM signing keys, Artifactory minting credential, Postgres and Kafka credentials)? | decisions.md |
| O-2 | Approve the proposed contract values in D-028: 6-month v1/v2 overlap, a link TTL cap of P7D, 410 `RESULTS_EXPIRED`, and the extra reason codes. | architecture.md 5.4a |
| O-3 | Minimum OpenShift version. Is 4.20 or later (needed for user namespaces, restricted-v3) required, or is 4.18 with restricted-v2 acceptable? | operations-guide.md, D-009 |
| O-4 | Targets for the engine quality gate (precision, recall, UNKNOWN rate), and who owns and labels the benchmark corpus. | CONTRIBUTING.md, phase 0 gate |
| O-5 | RPO and RTO targets. | operations-guide.md |
| O-6 | Owners: the polyglot image pipeline, secrets and their rotation, and the on-call for the service. | operations-guide.md |
| O-7 | A private channel for reporting security issues (license is MIT, set in the repo). | CONTRIBUTING.md |
| O-8 | For the later Snyk engine: may code or metadata go to Snyk's service? | faq.md, D-022 |

## Engineering, to close in phase 0

| # | Question | Source |
|---|---|---|
| E-1 | Freeze the request schema (`docs/contracts/scan-request.v1.schema.json`, now enforced by the `contracts` module). | R15 |
| E-2 | Priority classes above `default` for Kafka emitters. A config allow-list is the interim rule; decide on a signed field or a topic per class. | integration-guide.md |
| E-3 | Kafka partition counts, replication factor and ACL principal names. | operations-guide.md |
| E-4 | Secret names, and rotation periods for the GitHub App key, the IAM signing keys and the Artifactory minting credential. | operations-guide.md |
| E-5 | The Artifactory token-minting identity and the scopes it may grant. | operations-guide.md |
| E-6 | symbol-kb-builder's GitHub credential, and the egress allow-list for OSV and SAP Project KB. | operations-guide.md |
| E-7 | Exact IAM actions, and whether the IAM user also signs short-lived Job URLs. | operations-guide.md |
| E-8 | Postgres: operator or managed service, and sizing. (The partition tool is settled: a SQL function run by reachability-api, D-033.) | operations-guide.md |
| E-9 | S3 versioning, replication, encryption, and retention of source tarballs and input bundles. | operations-guide.md, faq.md |
| E-10 | Alert thresholds, ResourceQuota/LimitRange and emptyDir sizes, and the scanner MachineSet labels and taints. | operations-guide.md |
| E-11 | podFailurePolicy action for `DisruptionTarget` (Ignore, or fail and requeue). Proposed in D-036: `FailJob`, requeued as `POD_DISRUPTED`; awaits review. | operations-guide.md |
| E-12 | GitHub App permissions for submodules in other orgs. | operations-guide.md |
| E-13 | Which module and image run the source-fetch Job (D-030 proposes the `scanner` module in fetch mode for the first). Proposed in D-036: a separate `images/source-fetch` image; awaits review. | CONTRIBUTING.md |
| E-14 | Request body of the KB overrides admin API and the symbol FQN format per ecosystem. | CONTRIBUTING.md |
| E-15 | Formatter and static-analysis tools. Whether FORCE_VERDICT overrides need a second reviewer. | CONTRIBUTING.md |
| E-16 | Measured scan times per ecosystem, to replace the 8-minute assumption (D-020). Publish the toolchain versions in the polyglot image. | faq.md |
| E-17 | How to read a DT 5 API key's expiry for `reach_dt_apikey_expiry_days` (D-032). No documented v1 endpoint returns it for the calling key; UNVERIFIED whether the v2 service-account API does. Until then the metric is not exported. | operations-guide.md |
