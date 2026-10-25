# reachability-pod operations guide

Audience: platform and SRE teams running reachability-pod on Red Hat OpenShift.
Owner: Arik. Date: 2026-10-25.

> **Status: design stage. No code exists yet.** Every procedure in this guide is **planned** and untested. Names, keys and values come from [architecture.md](architecture.md) and [decisions.md](decisions.md). Where those documents mark a value as a proposed default or UNVERIFIED, this guide does the same. Placeholders look like `<this>`. Gaps are listed in [Open questions](#13-open-questions).

## Contents

1. [What you are running](#1-what-you-are-running)
2. [Prerequisites checklist](#2-prerequisites-checklist-planned)
3. [Namespace layout and security](#3-namespace-layout-and-security-planned)
4. [Deployment steps](#4-deployment-steps-planned)
5. [Configuration and secrets](#5-configuration-and-secrets-planned)
6. [Day-2 operations](#6-day-2-operations-planned)
7. [Monitoring and alerts](#7-monitoring-and-alerts-planned)
8. [Runbook](#8-runbook-planned)
9. [Backup, restore and disaster recovery](#9-backup-restore-and-disaster-recovery-planned)
10. [Upgrade procedure](#10-upgrade-procedure-planned)
11. [Pre-production verification](#11-pre-production-verification-planned)
12. [Quick reference](#12-quick-reference)
13. [Open questions](#13-open-questions)

## 1. What you are running

reachability-pod takes a scan request, fetches one GitHub commit, builds it, and decides for each Dependency-Track (DT) finding whether the vulnerable code is reachable. Results go to S3 as OCSF and VEX. Completion is announced on Kafka with presigned links.

| Component | Kind | Namespace | Notes |
|---|---|---|---|
| reachability-api | Deployment, 2+ replicas, stateless | `reach` | REST intake, Kafka intake consumer, status, bulk query, exports, admin API, sys-admin UI (D-026) |
| scan-controller | Deployment, 2 replicas, one leader | `reach` | Leader holds a Postgres advisory lock; standby takes over on lock loss |
| symbol-kb-builder | CronJob | `reach` | Fills the `kb` schema and refreshes the offline Go vulndb zip (D-014) |
| source-fetch Job | Job, one per scan | `reach-fetch` (trusted) | Clones the commit with submodules, uploads a tarball via presigned PUT (D-008) |
| scan Job | Job, one per scan, polyglot image | `reach-scan` (untrusted) | Builds against Artifactory, runs engines, uploads `summary.json` (D-009, D-010) |
| Postgres 18 | external or operator-managed | n/a | Schemas `scan` and `kb` (D-001) |

Key facts for operators:
- All state lives in Postgres. The controller is safe to restart at any time.
- Untrusted code runs only in `reach-scan`. That namespace reaches only Artifactory and our S3 bucket. It holds no GitHub, DT, Kubernetes or AWS credentials (D-008, D-009). Artifactory remotes are still a narrow way out, because they fetch whatever package names a build asks for; section 2.5 lists the controls (R16, D-040).
- Kafka publishing uses a transactional outbox. Delivery is at-least-once. Consumers dedupe on scan uid.
- Per-module build or engine failures give UNKNOWN verdicts, not failed Jobs. Only infrastructure failures fail a Job.

Scan states: QUEUED, FETCHING, SCANNING, PUBLISHING, then SUCCEEDED, PARTIAL, FAILED or CANCELLED. See architecture.md section 4.2 for transitions and retry rules.

## 2. Prerequisites checklist (planned)

Tick every item before step 1 of the deployment.

### 2.1 OpenShift

- [ ] OpenShift **4.20+ preferred**. User namespaces (`hostUsers: false`, restricted-v3 SCC) are GA in 4.20. On older clusters `reach-scan` runs restricted-v2 only (D-009, R5). User namespaces on 4.18/4.19 are UNVERIFIED.
- [ ] OVN-Kubernetes network plugin, so EgressFirewall (`k8s.ovn.org/v1`) is available.
- [ ] Permission to create MachineSets and a MachineAutoscaler for the scanner pool.
- [ ] Capacity for the scanner pool. Estimated peak: ~170 concurrent scan Jobs, ~680 vCPU at 4 vCPU per Job (D-020). These are estimates to be measured in phase 0 and phase 3.
- [ ] Cluster logging and Prometheus user-workload monitoring, for logs and the `reach_*` metrics.
- [ ] Image registry reachable from scanner nodes, for the polyglot image (pulled by digest).
- [ ] No Kata or gVisor is needed. The design does not use them (D-009).

### 2.2 Postgres 18

- [ ] A Postgres 18 instance, from an operator or a managed service (D-001).
- [ ] A database and a role for the services, with rights to create schemas `scan` and `kb`.
- [ ] Sizing for 10,000 scans/day (D-020). Postgres carries the queue load (D-006).
- [ ] Backups enabled (section 9).
- [ ] Network path from namespace `reach` only.
- [ ] Server-side TCP keepalives (`tcp_keepalives_idle`, `tcp_keepalives_interval`, `tcp_keepalives_count`), so Postgres notices a dead leader-lock session and frees the lock within a bounded time; for example 30s, 10s and 3 give about a minute. The controller's own side uses `tcpKeepAlive` and a socket timeout (D-035). The leader lock opens its own connection from `quarkus.datasource.jdbc.url`, `quarkus.datasource.username` and `quarkus.datasource.password`. It does not go through a Quarkus credentials provider, so if database credentials ever come from one (for example Vault database secrets), the lock connection needs the same values in those keys, or a code change.

### 2.3 Apache Kafka

Plain Apache Kafka. No vendor distribution is required (D-023). A schema registry is optional and off by default (`reach.kafka.schema-registry.enabled=false`).

- [ ] Topics created. Retention values are proposed defaults; partition counts are not yet set (see Open questions).

| Topic | Key | Retention (proposed) | Producer | Consumers |
|---|---|---|---|---|
| `appsec.reachability.requests.v1` | emitter's choice (ignored) | 7 d | emitters (CI, any service) | reachability-api |
| `appsec.reachability.requests.dlq.v1` | original key | 14 d | reachability-api | operators |
| `appsec.reachability.status.v1` | scanId | 7 d | scan-controller (outbox relay) | requesters |
| `appsec.reachability.results.v1` | DT project name + version | 7 d | scan-controller (outbox relay) | requesters, AppSec dashboard |
| `appsec.reachability.exports.v1` | exportId | 7 d | scan-controller (outbox relay) | export requesters |

- [ ] ACLs as in the producer and consumer columns above. reachability-api also needs Read on its consumer group.
- [ ] Security mode chosen: `reach.kafka.security` is PLAINTEXT by default; SASL or mTLS is configurable (D-024).
- [ ] Network path from namespace `reach` to the brokers.

### 2.4 AWS S3 and IAM

- [ ] One S3 bucket. Results go under `s3://<bucket>/<org>/<repo>/<commit>/<scanId>/` (D-016).
- [ ] Lifecycle rule for retention, default 1 year (`reach.retention.s3=P365D`, D-025).
- [ ] S3 VPC endpoint with an **endpoint policy that pins traffic to our bucket and account**. This is the real pin: a presigned URL for another bucket fails even inside the S3 CIDRs (architecture.md section 9).
- [ ] **IAM role for services** (reachability-api, scan-controller, symbol-kb-builder), assumed via IRSA/STS with a projected ServiceAccount token. Session length 1-12h.
- [ ] **IAM user for long-lived link signing** (scan-controller and reachability-api, signing only). Required because the default link TTL is 24h, and STS-signed links die when the session ends (max 12h) (D-017, R9). `reach.s3.signer=iam-user`.
- [ ] The list of AWS S3 CIDR ranges for the region, for the EgressFirewall `cidrSelector` rules. S3 IPs rotate (R10).

### 2.5 JFrog Artifactory

Artifactory is the only dependency source for scan Jobs (D-011).

- [ ] Remote repositories (with virtuals where needed), one set per ecosystem:

| Ecosystem | Needed | Injected into the scan Job as |
|---|---|---|
| Maven | Maven remote behind the virtual `maven-virtual` (`reach.scanner.artifactory.repositories.maven`) | generated `settings.xml` (`-s`/`-gs`) with one mirror, `mirrorOf *`, and the token as a Bearer header from the environment (D-043) |
| Gradle | the same virtual, which also needs a Gradle plugins remote (`plugins.gradle.org/m2`) for `plugins {}` blocks | `--init-script` replacing every repository (settings, `pluginManagement`, `buildscript`, project) with the mirror; the image's Gradle 9.8.2, never the wrapper (D-043) |
| Python | PyPI remote | `PIP_INDEX_URL`; `UV_DEFAULT_INDEX` + `UV_PYTHON_DOWNLOADS=never`; `POETRY_PYPI_MIRROR_URL` |
| Go | Go remote behind a virtual, plus GOSUMDB proxying | `GOPROXY=<art>/api/go/<virtual>`, `GONOPROXY=none`, `GOSUMDB` via Artifactory, `GOFLAGS=-mod=mod`, `GOTOOLCHAIN=local` |
| npm (npm, pnpm, Yarn) | npm remote | `npm_config_registry`, `NPM_CONFIG_USERCONFIG`; Yarn Berry `YARN_NPM_REGISTRY_SERVER` |

- [ ] A **token-minting identity**: an admin access token that scan-controller uses to mint one read-only token per scan Job (`POST /access/api/v1/tokens`) and revoke it when the Job ends (`DELETE /access/api/v1/tokens/<id>`). Store it in Vault KV v2 `reach/artifactory`, field `admin-token` (D-044). Token `expires_in` = `reach.artifactory.token-ttl-factor` (2.0) x the scan `activeDeadlineSeconds`.
- [ ] A group, by default `reach-scan` (`reach.artifactory.token-scope` = `applied-permissions/groups:reach-scan`), with read permission on the virtuals above and nothing else. The minted tokens carry only this group, so they are read-only and limited to those virtuals. Each token's user is the transient `reach-scan-<scanId>`, which is how request logs tell scans apart.
- [ ] `reach.artifactory.url` set to the JFrog platform URL (for example `https://artifactory.example`); repositories are expected under `/artifactory`.
- [ ] The Artifactory hostname, for the `reach-scan` EgressFirewall `dnsName` rule.

Exfiltration controls for the scan namespace (R16, D-040). Package names are data: a hostile build can encode source code in the names it asks a remote to fetch, and the remote passes them upstream. These settings narrow that channel:

- [ ] The scan Job's tokens can read only a dedicated virtual per ecosystem, not every repository on the instance.
- [ ] Those virtuals contain only remotes for curated upstreams (Maven Central, the plugin portal, registry.npmjs.org, pypi.org, proxy.golang.org) and local repositories for private packages. No remote points at a generic VCS host or a user-supplied URL.
- [ ] The Go remote resolves only through `proxy.golang.org`, with VCS fetching (direct `go-import` resolution) disabled; private Go modules come from a local repository. A module path on an unknown host then fails instead of making Artifactory contact that host.
- [ ] Where licensed, JFrog Curation (or an exclude pattern list) blocks unknown or newly published packages.
- [ ] Artifactory request logs for the scan tokens go to the SIEM, with alerts on bursts of 404s and on requested names with long random-looking segments or unknown Go module hosts.
- [ ] The residual risk (data leaking through package names that public upstreams see) is accepted in D-040; re-review it for orgs whose source is especially sensitive.
- [ ] Inputs for the weekly polyglot image build also come through Artifactory (D-010).

### 2.6 Dependency-Track 5.x

The service uses `/api/v1` endpoints only (D-003).

- [ ] **Read service account and API key**: VIEW_PORTFOLIO and VIEW_VULNERABILITY. Used with write-back OFF (the default, D-004).
- [ ] **Write-back service account and API key** (separate, proposed; only if write-back is used): VULNERABILITY_ANALYSIS_UPDATE for COMMENT and ANALYSIS modes; VULNERABILITY_ANALYSIS or VULNERABILITY_ANALYSIS_UPDATE for VEX mode.
- [ ] If DT portfolio ACLs are on, both accounts can see every project that will be scanned.
- [ ] Store the DT API key(s) statically in HashiCorp Vault KV v2 (D-032). Service-account keys expire (default 30 days, **max 366 days**), so the Vault owner rotates them; a team key does not expire (section 6.4).
- [ ] If write-back will be enabled: review DT v2 vuln policies. They may overwrite our analyses (R7, UNVERIFIED). Prefer LOG-mode policies, or CEL conditions that exclude our findings.

### 2.7 GitHub App

- [ ] A GitHub App with **Repository permissions: Contents read-only**.
- [ ] The App installed on every org or repo to be scanned, including repos referenced as submodules (submodules are cloned recursively, D-019).
- [ ] App ID in `reach.github.app-id`. Private key in Vault KV v2 at `reach/github`, field `private-key` (PKCS#1 as GitHub issues it, or PKCS#8), read by scan-controller as `reach.github.private-key` (D-032, D-036).
- [ ] On GitHub Enterprise Server, set `reach.github.api-url` to `https://<host>/api/v3`.
- [ ] Installation tokens are minted per scan and given only to the source-fetch Job (D-021).

### 2.8 OIDC

- [ ] An OIDC client for reachability-api, configured via `quarkus.oidc.*` (D-024). Any provider works.
- [ ] The audience: tokens for reachability-api must carry `reachability-api` in `aud` (Keycloak: an audience mapper on the client; Entra ID: the application ID URI). Set `REACH_OIDC_AUDIENCE` if you use another value. A production start without an audience fails (D-039). The issuer is checked through the discovery document at `quarkus.oidc.auth-server-url`.
- [ ] Three roles delivered as claims: **scan-submitter**, **results-reader**, **sys-admin**. The claim is `groups` by default (`quarkus.oidc.roles.role-claim-path`). Map provider group names to roles with `quarkus.http.auth.roles-mapping."<group>"=<role>`.
- [ ] Group membership decided: who submits scans, who reads results, who administers.
- [ ] The role claim for client-credentials (machine) tokens. Many providers put service-account roles outside `groups` (Keycloak: `realm_access/roles` or `resource_access/<client>/roles`; Entra ID: `roles`). Either add a groups mapper to the emitter clients or set `quarkus.oidc.roles.role-claim-path` to the claim they use.

### 2.9 Network egress inventory

- [ ] From `reach`: DT, S3, Kafka, Artifactory, GitHub API, OIDC provider.
- [ ] From `reach-fetch`: GitHub and S3 only.
- [ ] From `reach-scan`: Artifactory and S3 only.

## 3. Namespace layout and security (planned)

Three namespaces. Trust decreases from left to right (D-008, D-009).

| Control | `reach` (services) | `reach-fetch` (trusted) | `reach-scan` (untrusted) |
|---|---|---|---|
| Runs | reachability-api, scan-controller, symbol-kb-builder | source-fetch Jobs | scan Jobs |
| SCC | restricted-v2 | restricted-v2 | restricted-v2; restricted-v3 + `hostUsers: false` on OCP 4.20+ |
| Nodes | general | general | dedicated tainted scanner MachineSet; toleration + nodeSelector |
| ServiceAccount | RBAC as below | dedicated, no RBAC, `automountServiceAccountToken: false` | same |
| Egress | DT, S3, Kafka, Artifactory, GitHub API, OIDC | GitHub + S3 | Artifactory + S3 only |
| Pod settings | standard | read-only root FS, emptyDir with `sizeLimit` | read-only root FS; emptyDir `/work`, `/tmp`, `$HOME` with `sizeLimit`; ephemeral-storage limits |
| Job settings | n/a | `backoffLimit: 0`, `restartPolicy: Never`, `activeDeadlineSeconds`, `ttlSecondsAfterFinished`, podFailurePolicy | same |
| Quota | n/a | LimitRange, ResourceQuota incl. `count/jobs.batch` | same |

### 3.1 RBAC for scan-controller

scan-controller gets a Role in both `reach-fetch` and `reach-scan`:
- create, get, list, watch, delete on `jobs`;
- get, list, watch on `pods` and `pods/log` (the fetch exit code is read from the pod);
- create, get, delete on `secrets` (per-job Secrets, created after their Job with the Job already as owner; the controller reads the fetch token back to revoke it, then deletes the Secret).

No other workload gets Kubernetes API rights. Job pods have no ServiceAccount token at all.

### 3.2 Egress layering

Three layers. Each one covers a gap in the others.
1. **NetworkPolicy**, default deny for ingress and egress. Egress is allowed only to DNS (`openshift-dns`) and to `0.0.0.0/0` minus pod, service and machine CIDRs and `169.254.169.254`. NetworkPolicy has no FQDN peers, so it only blocks in-cluster and metadata traffic.
2. **EgressFirewall** (`k8s.ovn.org/v1`, one per namespace, named `default`). Allow `cidrSelector` for AWS S3 ranges, because S3 IPs rotate. Allow `dnsName` for Artifactory (`reach-scan`) and GitHub (`reach-fetch`). OVN re-resolves names at most every 30 min. The last rule denies `0.0.0.0/0`.
3. **S3 VPC endpoint policy** pins S3 traffic to our bucket and account.

A repo `.npmrc` (or similar) can override registry config. The egress block is the backstop (R11).

### 3.3 Illustrative EgressFirewall for `reach-scan`

Illustrative only. Replace the placeholders. Keep the final deny rule.

```yaml
# ILLUSTRATIVE - planned, not tested
apiVersion: k8s.ovn.org/v1
kind: EgressFirewall
metadata:
  name: default            # must be "default"; one per namespace
  namespace: reach-scan
spec:
  egress:
    - type: Allow
      to:
        dnsName: <artifactory-host>        # e.g. the Artifactory FQDN
    - type: Allow
      to:
        cidrSelector: <aws-s3-cidr-1>       # AWS S3 range for the region
    - type: Allow
      to:
        cidrSelector: <aws-s3-cidr-2>       # one rule per S3 range
    - type: Deny
      to:
        cidrSelector: 0.0.0.0/0
```

For `reach-fetch`, replace the Artifactory rule with `dnsName: <github-host>` rules. Keep the S3 rules and the final deny.

### 3.4 Illustrative NetworkPolicy for `reach-scan`

Illustrative only. The CIDRs to exclude are cluster-specific.

```yaml
# ILLUSTRATIVE - planned, not tested
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: default-deny-with-egress
  namespace: reach-scan
spec:
  podSelector: {}
  policyTypes: [Ingress, Egress]
  ingress: []                    # no ingress at all
  egress:
    - to:                        # cluster DNS
        - namespaceSelector:
            matchLabels:
              kubernetes.io/metadata.name: openshift-dns
      ports:
        - {protocol: UDP, port: 53}
        - {protocol: TCP, port: 53}
        - {protocol: UDP, port: 5353}
        - {protocol: TCP, port: 5353}
    - to:                        # outside the cluster only; EgressFirewall narrows further
        - ipBlock:
            cidr: 0.0.0.0/0
            except:
              - <pod-cidr>
              - <service-cidr>
              - <machine-cidr>
              - 169.254.169.254/32
```

Apply the same shape to `reach-fetch`. For `reach`, add ingress from the router and egress to Postgres and Kafka as your cluster needs.

### 3.5 Illustrative scan Job pod spec

Illustrative only. scan-controller creates these Jobs; operators do not. Values are the proposed defaults from architecture.md section 12.

```yaml
# ILLUSTRATIVE - planned, not tested
apiVersion: batch/v1
kind: Job
metadata:
  namespace: reach-scan
  generateName: scan-
  labels:
    <scan-label-key>: <scanId>           # controller watches Jobs by label
spec:
  backoffLimit: 0                        # D-009
  activeDeadlineSeconds: 2700            # reach.job.scan.active-deadline = PT45M
  ttlSecondsAfterFinished: 600           # reach.job.ttl-after-finished
  podFailurePolicy:
    rules:
      - action: Ignore                   # eviction / node loss is transient (see Open questions)
        onPodConditions:
          - type: DisruptionTarget
  template:
    spec:
      restartPolicy: Never
      serviceAccountName: <scan-sa>      # dedicated, no RBAC
      automountServiceAccountToken: false
      hostUsers: false                   # only on OCP 4.20+ with restricted-v3
      nodeSelector:
        <scanner-node-label>: "true"
      tolerations:
        - key: <scanner-taint-key>
          operator: Exists
          effect: NoSchedule
      securityContext:
        runAsNonRoot: true               # arbitrary UID from the namespace range
        seccompProfile:
          type: RuntimeDefault
      containers:
        - name: scanner
          image: <registry>/<polyglot-image>@sha256:<digest>   # always by digest
          securityContext:
            allowPrivilegeEscalation: false
            readOnlyRootFilesystem: true
            capabilities:
              drop: [ALL]
          resources:
            requests: {cpu: "4", memory: 16Gi, ephemeral-storage: 50Gi}
            limits:   {cpu: "4", memory: 16Gi, ephemeral-storage: 50Gi}
          volumeMounts:
            - {name: work, mountPath: /work}
            - {name: tmp,  mountPath: /tmp}
            - {name: home, mountPath: <home-dir>}
            - {name: job-secret, mountPath: <secret-dir>, readOnly: true}  # files, never env
      volumes:
        - {name: work, emptyDir: {sizeLimit: <size>}}
        - {name: tmp,  emptyDir: {sizeLimit: <size>}}
        - {name: home, emptyDir: {sizeLimit: <size>}}
        - name: job-secret
          secret:
            secretName: <per-job-secret>  # Artifactory token + presigned URLs, owner-referenced to the Job
```

### 3.6 Scanner MachineSet

- Dedicated MachineSet for `reach-scan` only. Taint it so no other pods land there.
- A MachineAutoscaler on it, min and max configurable. It reacts to pending scan pods.
- Set `reach.queue.global-max-running` at or below the MachineSet's max capacity. The queue, not the cluster, then absorbs bursts.
- A DaemonSet pre-pulls the polyglot image on these nodes (D-010).

### 3.7 ResourceQuota and LimitRange

For `reach-fetch` and `reach-scan`:
- LimitRange with default and max requests/limits, including ephemeral storage.
- ResourceQuota on CPU, memory, ephemeral storage and `count/jobs.batch`. The Job count quota caps etcd load if the controller misbehaves.
- Size the `reach-scan` quota from `reach.queue.global-max-running` x the per-Job resources (4 CPU / 16Gi / 50Gi proposed).

Exact quota values are not yet set (see Open questions).

## 4. Deployment steps (planned)

Run these in order. Each step depends on the ones before it.

1. **Confirm the OpenShift version.** 4.20+ allows restricted-v3 and `hostUsers: false` for `reach-scan`. Below 4.20, use restricted-v2 (R5).
2. **Create namespaces** `reach`, `reach-fetch`, `reach-scan`.
3. **Create the scanner MachineSet** with its taint and labels, and its MachineAutoscaler (section 3.6).
4. **Apply SCC bindings.** restricted-v2 everywhere; restricted-v3 in `reach-scan` on 4.20+.
5. **Create ServiceAccounts.** Dedicated SAs with no RBAC for `reach-fetch` and `reach-scan`. Bind the scan-controller Role in both (section 3.1).
6. **Apply network controls.** NetworkPolicy (default deny) and EgressFirewall `default` in each namespace (sections 3.2-3.4). Enable OVN ACL logging on the deny rules so violations can alert.
7. **Apply LimitRange and ResourceQuota** in `reach-fetch` and `reach-scan`.
8. **Prepare Postgres 18.** Create the database and role. Schemas `scan` and `kb` are created by the service's migrations on first start (see section 10 and Open questions).
9. **Create Kafka topics and ACLs** (section 2.3).
10. **Prepare AWS.** Bucket, lifecycle rule, VPC endpoint policy, IAM role for services (IRSA/STS), IAM user for signing (section 2.4).
11. **Prepare Artifactory.** Remotes and virtuals per ecosystem, and the token-minting identity (section 2.5).
12. **Prepare Dependency-Track.** Read service account and key; write account and key only if write-back is planned (section 2.6).
13. **Create and install the GitHub App** (section 2.7).
14. **Register the OIDC client** and the three role groups (section 2.8).
15. **Store secrets in HashiCorp Vault** (section 5.2): the DT API key (KV v2, static), plus the proposed GitHub App key, IAM signing keys, Artifactory minting credential, and Postgres and Kafka credentials. Configure the Vault Kubernetes auth role for the `reach` ServiceAccounts (D-032).
16. **Build and publish the polyglot image** from its pinned manifest. Record the digest. Deploy the pre-pull DaemonSet on scanner nodes.
17. **Deploy reachability-api** (2+ replicas) and **scan-controller** (2 replicas) in `reach`. Check that exactly one controller holds the leader lock.
18. **Deploy the symbol-kb-builder CronJob** (`reach.kb.cron`, hourly proposed). Trigger one run by hand to seed the `kb` schema and the offline Go vulndb zip.
19. **Check the Vault integration.** Both services start, read the DT key from Vault, and expose `reach_dt_apikey_expiry_days` (D-032).
20. **Smoke test.** Submit one scan by `POST /api/v1/scans` and one by `appsec.reachability.requests.v1`. Check that each reaches SUCCEEDED or PARTIAL, that a Completed event appears on `appsec.reachability.results.v1`, and that the presigned links work.
21. **Run the security checks** in section 11 before taking production traffic.

## 5. Configuration and secrets (planned)

### 5.1 Key configuration

All values are proposed defaults unless a decision sets them. The full list is in architecture.md section 12. Priority classes and caps here only seed the database. After that, sys-admins change them at runtime via the admin API (D-007).

| Key | Default | Note |
|---|---|---|
| `reach.queue.classes.<name>.weight` | critical 1000, pr 500, default 100, bulk 10 | proposed |
| `reach.queue.classes.<name>.aging-per-min` | 1 | proposed |
| `reach.queue.classes.bulk.max-running` | 20 | proposed |
| `reach.queue.global-max-running` | 170 | D-020 peak estimate |
| `reach.queue.org-max-running` / `repo-max-running` | unset / 2 | proposed |
| `reach.queue.claim-batch` | 1 | proposed |
| `reach.retry.max-attempts` / `backoff` | 3 / PT1M exponential | proposed |
| `reach.dedupe.window` | P1D | proposed |
| `reach.job.scan.active-deadline` | PT45M | proposed |
| `reach.job.fetch.active-deadline` | PT10M | proposed |
| `reach.job.ttl-after-finished` | 600s | proposed (range 300-3600s) |
| `reach.job.scan.cpu` / `memory` / `ephemeral-storage` | 4 / 16Gi / 50Gi | 4 vCPU from D-020; others proposed |
| `reach.k8s.max-pending-pods` | 20 | proposed |
| `reach.engine.<id>.enabled` / `.timeout` | true / 50% of scan deadline per module | proposed |
| `reach.engine.java.call-graph` | CHA | D-013 |
| `reach.kb.cron` | hourly | proposed |
| `reach.dt.url`, `reach.dt.api-key-read`, `reach.dt.api-key-write` | none | required; write key only for write-back |
| `reach.dt.page-size` | 100 | DT default |
| `reach.dt.max-concurrent` | 8 | scans whose DT data is loaded in parallel |
| `reach.dt.repo-binding` | WRITEBACK | what a DT project that names no repo allows: `WRITEBACK` lowers VEX/ANALYSIS to COMMENT, `REQUIRED` fails the scan with `DT_REPO_UNBOUND`, `OFF` skips the check (D-037). Switch to REQUIRED once projects carry a `vcs` reference or `reach-repo=` tag |
| `quarkus.vault.url`, `quarkus.vault.authentication.kubernetes.role` | none / `scan-controller` | Vault address and Kubernetes auth role; the DT key is read from KV v2 `reach/dt` field `api-key-read` |
| `reach.dt.writeback.mode` | OFF | D-004 |
| `reach.dt.writeback.verdicts` | NOT_REACHABLE | proposed |
| `reach.s3.bucket`, `reach.s3.region` | none | required |
| `reach.s3.link-ttl` | PT24H | D-017; over 12h needs `reach.s3.signer=iam-user` |
| `reach.s3.signer` | iam-user | follows from the 24h default |
| `reach.retention.s3` / `reach.retention.postgres` | P365D / P90D | D-025 |
| `reach.retention.cron` | `0 45 3 * * ?` | when reachability-api drops expired Postgres partitions (D-038) |
| `reach.intake.max-request-bytes` | 262144 | largest request accepted on REST (413) and Kafka (DLQ `PAYLOAD_TOO_LARGE`) (D-039) |
| `REACH_OIDC_AUDIENCE` (`quarkus.oidc.token.audience`) | `reachability-api` | required `aud` of every token (D-039) |
| `reach.results.sync-max-rows` / `page-size` | 10000 / 500 | proposed |
| `reach.kafka.bootstrap-servers`, `reach.kafka.security` | none / PLAINTEXT | SASL or mTLS configurable (D-024) |
| `reach.kafka.topics.*` | names in section 2.3 | D-005, D-017 |
| `reach.kafka.schema-registry.enabled` | false | D-023 |
| `quarkus.oidc.roles.role-claim-path` | `groups` | provider via `quarkus.oidc.*`; group-to-role mapping via `quarkus.http.auth.roles-mapping` (D-024, D-033) |
| `reach.github.allowed-hosts` | `github.com` | hosts a request's `repo.url` may name |
| `reach.partitions.months-ahead` | 3 | monthly `scan.scan` partitions kept ready |
| `reach.artifactory.url`, `reach.artifactory.token-ttl-factor` | none / 2.0 | JFrog platform URL; token lifetime factor (D-011, D-044) |
| `reach.artifactory.token-scope` | `applied-permissions/groups:reach-scan` | scope of each scan's token (D-044) |
| `reach.scanner.artifactory.repositories.maven` / `.gradle` | `maven-virtual` | scanner image config: the virtual each build tool resolves from (D-011) |
| `reach.github.app-id`, `reach.github.private-key` | none | required |

### 5.2 Secrets

Secret names are not defined yet. The names below are placeholders. Owners are not assigned yet. Both are in [Open questions](#13-open-questions).

| Secret (placeholder name) | Namespace | Contents | Used by | Rotation period | Owner |
|---|---|---|---|---|---|
| `<dt-read-key>` (Vault KV v2 path) | Vault | DT read API key (`reach.dt.api-key-read`) | api, controller | static; rotated by the Vault owner; SA keys max 366d (D-032) | Vault owner |
| `<dt-write-key>` (Vault KV v2 path) | Vault | DT write API key (`reach.dt.api-key-write`); only if write-back is on | controller | same as read key | Vault owner |
| `<github-app-key>` (Vault KV v2 `reach/github`, field `private-key`) | Vault | GitHub App private key (`reach.github.private-key`) | controller | long-lived; rotated by the GitHub App owner; picked up after the Vault cache period (D-036) | GitHub App owner |
| `<s3-signer-iam-user>` | `reach` | IAM user access key pair, for presigning links | controller, api (signing only) | long-lived, rotate; period TBD (R9) | TBD |
| `<artifactory-admin>` (Vault KV v2 `reach/artifactory`, field `admin-token`) | Vault | admin access token for minting and revoking scan tokens (`reach.artifactory.admin-token`) | controller | rotated by hand by the Artifactory owner; picked up after the Vault cache period (D-044) | Artifactory owner |
| `<postgres-credentials>` | `reach` | Postgres user and password, or client certificate | api, controller, kb-builder | TBD | TBD |
| `<kafka-credentials>` | `reach` | SASL or mTLS material, if `reach.kafka.security` is not PLAINTEXT | api, controller | TBD | TBD |
| `<oidc-client>` | `reach` | OIDC client credentials, if the client is confidential | api | TBD | TBD |
| per-job Secret `reach-fetch-<scanId>-<attempt>` (created by controller, immutable) | `reach-fetch` | files `github-token`, `source-put-url` | one source-fetch Job | GitHub-issued short-lived; never persisted; owner-referenced to the Job | scan-controller |
| per-job Secret `reach-scan-<scanId>-<attempt>` (created by controller, immutable) | `reach-scan` | files `source-get-url`, `input-get-url`, `summary-put-url`, `artifactory-token`, `artifactory-token-id` | one scan Job | token `expires_in` = 2x `activeDeadlineSeconds`, revoked and the Secret deleted when the Job ends (D-044); URLs `activeDeadlineSeconds` + margin; owner-referenced to the Job | scan-controller |

AWS access for services has no Secret. It uses IRSA/STS with a projected ServiceAccount token, 1-12h sessions.

Rules:
- Mount secrets as files. Job logs must never contain tokens.
- Per-job Secrets are owner-referenced to their Job, so they are deleted with it.
- No long-lived credential ever reaches `reach-fetch` or `reach-scan` (D-021).

## 6. Day-2 operations (planned)

### 6.1 Scaling

- **reachability-api**: scale horizontally. Kafka intake parallelism is bounded by the partition count of `appsec.reachability.requests.v1`.
- **scan-controller**: do not scale beyond 2. Only the leader works; the second replica is a standby. Its work is I/O-bound.
- **Scan throughput**: tune with the global, org and repo caps and the priority-class caps. Use `GET/PUT /api/v1/admin/concurrency-limits` and `GET/PUT /api/v1/admin/priority-classes`. Changes hot-reload (NOTIFY `reach_policy` plus a periodic refresh). No redeploy is needed.
- **Scanner nodes**: change the MachineAutoscaler min and max. Keep `reach.queue.global-max-running` at or below the MachineSet's max capacity.
- **Back-pressure**: the claim loop pauses when running scans reach the global cap, or when pending scan pods exceed `reach.k8s.max-pending-pods`. A growing queue is normal under burst. Intake stays fast because it only inserts.

### 6.2 Polyglot image rebuild

Cadence: **weekly**, from a pinned manifest, with all inputs pulled through Artifactory (D-010).

Today the image is `images/scanner/Dockerfile`: the digest-pinned `gradle:9.8.2-jdk25-ubi` base (UBI 10, Temurin 25), zstd, and Maven 3.9.16 checked against its SHA-512 (D-043). The build context is `scanner/target` after `mvn package`. Dependabot proposes base-image digest updates weekly. The Python, Go and Node toolchains are added with their engines.

1. Update the pinned manifest (toolchains, engines, version managers).
2. Build the image. Pull everything through Artifactory.
3. Run the benchmark (CONTRIBUTING.md, "Benchmark and quality gate") against the new image.
4. Publish the image and note its digest.
5. Update the DaemonSet to pre-pull the new digest on scanner nodes. Wait until it is pulled.
6. Point scan-controller at the new digest.
7. Watch the UNKNOWN rate and `reach_build_failures_total{tool}` for a day.

Notes:
- Each scan records the digest it used (`scan.image_digest`, and `image_digest` in the Completed event). Use it to explain result changes after a rebuild.
- A toolchain version not in the image fails that module with BUILD_FAILED. There are no runtime toolchain downloads. Adding versions to the image is the fix.

### 6.3 Knowledge base updates

- symbol-kb-builder runs on `reach.kb.cron` (hourly, proposed). It is incremental by advisory modified time.
- Sources, in precedence order: Go OSV `imports.symbols`, GHSA `vulnerable_functions`, fix-commit method diffs (OSV FIX refs, SAP Project KB), then manual overrides (D-014).
- It also refreshes the offline Go vulndb zip used by govulncheck.
- A stale KB lowers verdict levels but never blocks scans. Watch `reach_kb_age_seconds{source}`.
- Manual curation: sys-admins use `GET/POST/DELETE /api/v1/admin/kb/overrides` or the sys-admin console. Overrides apply last. See CONTRIBUTING.md for the curation workflow.

### 6.4 Credential rotation

| Credential | How (planned) |
|---|---|
| DT API keys | Static in Vault, owned by the Vault owner (D-032). To rotate, create a new key in DT, write it to Vault, wait one `reach.dt.api-key-refresh` interval, then revoke the old key. Service-account keys expire (max 366d), so watch `reach_dt_apikey_expiry_days`. |
| Artifactory per-job tokens | Nothing to rotate. Each token is revoked when its Job ends and expires at 2x the scan deadline anyway. To rotate the admin token, create a new one, write it to Vault `reach/artifactory`, wait one Vault cache period, then revoke the old one (D-044). |
| GitHub installation tokens | Nothing to rotate. GitHub issues them short-lived per scan. |
| GitHub App private key | Rotated by the GitHub App owner. Add the new key in GitHub, update the Secret, restart or reload the controller, then delete the old key in GitHub. |
| IAM user keys (signing) | Create a second access key. Update the Secret. Keep the old key active for at least the longest link TTL in use (24h default) so issued links keep working, then deactivate and delete it. Consumers can re-sign links with `POST /api/v1/scans/{scanId}/links`. |
| AWS service credentials | Nothing to rotate. IRSA/STS sessions are 1-12h. |

### 6.5 Postgres partition maintenance

Tables `scan.scan`, `scan.finding` and `scan.call_path` are partitioned monthly by `created_at` (D-025). Queue and policy tables are not partitioned.

- **Ahead of time**: make sure next month's partitions exist before the month starts. Missing partitions make inserts fail.
- **Retention** (D-038): reachability-api runs `scan.apply_retention` daily at `reach.retention.cron` (`0 45 3 * * ?`). It drops each monthly partition whose whole month is older than `reach.retention.postgres` (P90D default), so rows live up to one month longer than the setting. `call_path` and `finding` go before `scan`. Several replicas may run it at once (advisory lock). It refuses a period under one day. To run it by hand: `SELECT scan.apply_retention(interval '90 days');` (returns the number of partitions dropped).
- `scan.request_key` and `scan.scan_queue` have no partitions. `apply_retention` first deletes their rows that point at scans past the cutoff, so no key outlives its scan. A `metadata.uid` older than retention can then start a new scan.
- Keys in `scan.writeback_policy` and `scan.concurrency_limit` must be lower-case (`acme/payments`), because intake stores org and repo lower-case. A CHECK constraint rejects mixed-case keys. Upgrading to changeset 004 lower-cases existing keys; where both `ACME/Payments` and `acme/payments` exist, the lower-case row is kept.
- If every priority class is disabled, REST answers 503 `MISCONFIGURED` and Kafka intake retries and then stops its channel; the log says `no priority class is enabled`. Enable `default` again.
- `scan.outbox`: published rows are purged.
- Do not delete rows by hand from partitioned tables. Drop partitions.

The tool that creates and drops partitions is not decided yet (see Open questions).

### 6.6 Retention

| Data | Default | Mechanism |
|---|---|---|
| S3 results and inputs | 1 year (`reach.retention.s3=P365D`) | S3 lifecycle rule |
| Postgres scan state | 90 days (`reach.retention.postgres=P90D`) | monthly partition drops |
| Kafka topics | 7 d; DLQ 14 d (proposed) | topic retention |
| Finished Jobs, Pods, Secrets | 600s after finish (proposed) | `ttlSecondsAfterFinished` |

After S3 objects expire, presigned links and re-sign stop working (D-025).

## 7. Monitoring and alerts (planned)

### 7.1 Metrics

Exported via Micrometer for Prometheus.

| Metric | What it tells you |
|---|---|
| `reach_queue_depth{class}` | QUEUED scans per priority class |
| `reach_queue_wait_seconds{class}` | time from enqueue to claim |
| `reach_scans_total{status,reason}` | terminal outcomes and failure reasons |
| `reach_scan_duration_seconds{stage,ecosystem}` | time per stage |
| `reach_verdicts_total{verdict,level,ecosystem}` | verdict mix, including UNKNOWN and coarse levels |
| `reach_engine_duration_seconds{engine}` | engine run time |
| `reach_build_failures_total{tool}` | build failures per tool (mvn, gradle, pip, npm, ...) |
| `reach_jobs{ns,phase}` | Jobs per namespace and phase; finished Jobs are etcd load (planned) |
| `reach_jobs_created_total{stage}` | fetch and scan Jobs created by the controller |
| `reach_jobs_finished_total{stage,result}` | Jobs seen Complete or Failed |
| `reach_github_request_seconds{endpoint,code}` | GitHub App calls (installation, access_token, commit): latency and error codes |
| `reach_artifactory_request_seconds{endpoint,code}` | Artifactory token calls (create, revoke): latency and error codes; failed creates show up as `ARTIFACTORY_UNAVAILABLE` retries |
| `reach_outbox_lag_seconds` | age of the oldest unpublished outbox row |
| `reach_dt_request_seconds{endpoint,code}` | DT latency and error codes |
| `reach_dt_apikey_expiry_days` | days until the DT key expires (not implemented yet, see open question E-17) |
| `reach_controller_leader` | 1 on the replica holding the leader lock; exactly one replica should report 1 |
| `reach_dt_analysis_divergence_total` | DT analysis differs from our last write-back (R7) |
| `reach_kb_age_seconds{source}` | KB freshness per source |

Also use platform metrics: pending pods in `reach-scan`, node count of the scanner MachineSet, Kafka consumer lag for reachability-api, DLQ topic size, Postgres health.

Tracing: one OpenTelemetry trace per scan. `traceparent` propagates through Kafka headers, the outbox and Job env.
Logs: JSON. The controller fetches `pods/log` before TTL deletion and ships it to cluster logging, tagged with scanId.

### 7.2 Alerts

Thresholds are not set yet (see Open questions). Use `<threshold>` until phase 0 and phase 3 give measurements.

| Alert | Condition (sketch) | Severity (proposed) |
|---|---|---|
| Queue backlog | `reach_queue_wait_seconds` p95 for class `critical` or `pr` above `<threshold>` | page |
| Build failures spike | rate of `reach_build_failures_total{tool}` above `<threshold>` or sharp rise vs last week | ticket |
| DT unavailable | `reach_dt_request_seconds{code=~"5.."}` or timeouts above `<threshold>` for N minutes | page |
| DT key near expiry | `reach_dt_apikey_expiry_days` below `<threshold>` | ticket, then page |
| DT write-back divergence | `reach_dt_analysis_divergence_total` increasing | ticket |
| S3 errors | `reach_scans_total{status="FAILED"}` with S3 reasons, or S3 5xx in logs | page |
| Outbox lag | `reach_outbox_lag_seconds` above `<threshold>` | page |
| DLQ growth | messages in `appsec.reachability.requests.dlq.v1` increasing | ticket |
| etcd / Job buildup | `reach_jobs{phase="finished"}` above `<threshold>` | ticket |
| UNKNOWN rate rising | share of `reach_verdicts_total{verdict="UNKNOWN"}` above `<threshold>` | ticket |
| KB stale | `reach_kb_age_seconds` above `<threshold>` | ticket |
| No leader | no scan-controller replica holds the advisory lock | page |
| Egress violation attempt | OVN ACL log hits on a deny rule in `reach-scan` or `reach-fetch` | security ticket |

## 8. Runbook (planned)

Format: symptom, then checks, then actions. All steps are planned and untested.

### 8.1 Queue backlog

**Symptom**: `reach_queue_depth` grows and `reach_queue_wait_seconds` p95 is high.

Check:
1. Is the controller leader alive and claiming? Look at its logs and the advisory lock.
2. Are running scans at `reach.queue.global-max-running`? Then the cap is the limit.
3. Are pending scan pods above `reach.k8s.max-pending-pods`? Then the cluster is the limit. Check the MachineAutoscaler and scanner node count.
4. Is one org or repo at its cap while others wait? Check the sys-admin queue view (`GET /api/v1/admin/queue`).
5. Is a bulk burst filling slots? Check `reach_queue_depth{class="bulk"}` and the bulk cap.
6. Is a dependency slow (DT, S3, Artifactory) and making each scan longer?

Action:
- Raise the MachineAutoscaler max, then raise the global cap to match.
- Lower the bulk cap, or raise the weight of urgent classes, via `PUT /api/v1/admin/priority-classes`.
- If the leader is stuck, restart it. The standby takes the lock and reconciles all non-terminal scans.
- For a single stuck scan, use `POST /api/v1/admin/scans/{id}/requeue`.

### 8.2 Build failures spike

**Symptom**: `reach_build_failures_total{tool}` jumps. More UNKNOWN / BUILD_FAILED verdicts and PARTIAL scans.

Check:
1. Which `tool` label? One tool points to one ecosystem.
2. Did the polyglot image digest change recently? Compare `image_digest` on failing and passing scans.
3. Is Artifactory healthy? Is a remote down or returning errors?
4. Are the failures in many repos or a few? A few repos often means a toolchain version not in the image, or a repo-level registry override (`.npmrc` etc.).
5. Are EgressFirewall deny hits rising? A build may be trying a host other than Artifactory.

Action:
- Image regression: roll the controller back to the previous digest (section 10.3).
- Artifactory outage: fix the remote. Re-submit the affected scans.
- Missing toolchain version: add it to the image manifest and rebuild. There are no runtime downloads.
- Repo overrides registry: expected. The repo owner removes the override; the egress block stays.

### 8.3 Dependency-Track unavailable

**Symptom**: `reach_dt_request_seconds` shows 5xx or timeouts. Scans go back to QUEUED with backoff.

Check:
1. DT health and its own logs.
2. Is the API key valid? A 401 or 403 means an expired or revoked key. Check `reach_dt_apikey_expiry_days`.
3. Network path from `reach` to DT.

Action:
- Transient outage: no action. Scans retry with backoff up to `reach.retry.max-attempts`, then FAIL. Re-submit those after DT is back.
- Expired key: rotate it now (section 6.4).
- Long outage: consider lowering class caps so retries do not pile up.
- Write-back errors never change scan status. They are logged per finding.

### 8.4 S3 errors

**Symptom**: scans fail in FETCHING or PUBLISHING with S3 errors. Jobs cannot upload or download.

Check:
1. AWS status for the region.
2. IRSA/STS: can the services assume the role? Look for credential errors in controller logs.
3. EgressFirewall: did the AWS S3 ranges change (R10)? Deny hits towards S3 IPs mean the CIDR list is stale.
4. VPC endpoint policy: does it still allow our bucket and account?
5. Large repos: SOURCE_TOO_LARGE means a tarball over 5 GB (single PUT limit). That is a permanent failure, not an outage.
6. Signer: are links failing after an IAM user key change? See 6.4.

Action:
- Update the S3 CIDRs in the EgressFirewalls of `reach-fetch` and `reach-scan`.
- Fix the IAM role trust or the endpoint policy.
- PUBLISHING S3 errors retry in place. FETCHING errors requeue with backoff. Re-submit scans that ran out of attempts.

### 8.5 Kafka DLQ growth

**Symptom**: `appsec.reachability.requests.dlq.v1` keeps growing.

Check:
1. Read a few DLQ records. The error headers say why they were rejected.
2. Usually it is schema-invalid input: wrong `schema_version`, a branch name instead of a 40-hex SHA, missing `dt_project` name or version.
3. Group by emitter (`metadata.product.name`) to find the source.

Action:
- Contact the emitter owner with the error and the schema link.
- After the emitter is fixed, it re-sends. Do not replay DLQ records blindly; they fail again unless fixed.
- If valid records land in the DLQ, suspect a schema version mismatch after an upgrade (section 10).

### 8.6 etcd and Job object buildup

**Symptom**: `reach_jobs{phase="finished"}` is high. API server or etcd is slow. `count/jobs.batch` quota is hit.

Check:
1. Is `ttlSecondsAfterFinished` set on new Jobs?
2. Is the controller collecting status, `summary.json` and logs? It must finish collecting before TTL deletion.
3. Are Events piling up in `reach-fetch` and `reach-scan`?

Action:
- Lower `reach.job.ttl-after-finished` (range 300-3600s).
- Let the controller delete collected Jobs directly.
- If the controller is down, restore it first. Jobs it has not collected yet still hold results.

### 8.7 UNKNOWN rate rising

**Symptom**: the share of `reach_verdicts_total{verdict="UNKNOWN"}` goes up.

Check the `reason` breakdown first. Each reason has its own cause.

| Reason | Likely cause | Action |
|---|---|---|
| BUILD_FAILED | image change, Artifactory issue, missing toolchain | see 8.2 |
| ENGINE_TIMEOUT | larger repos, slower nodes, engine regression | check `reach_engine_duration_seconds{engine}`; tune `reach.engine.<id>.timeout` or the scan deadline |
| NO_SYMBOL_DATA | KB stale or new advisories without symbols | check `reach_kb_age_seconds`; run symbol-kb-builder; curate overrides |
| UNSUPPORTED_LANGUAGE | more C/C++ or non-day-1 repos | expected (D-012); not an incident |
| COMPONENT_NOT_RESOLVED (proposed) | finding purl not found in any module; incomplete SBOM graph in DT | check the DT SBOM `dependencies` graph for that project |

Also check `reach_verdicts_total{level}`. A shift to CLASS_OR_PACKAGE or IMPORT means fallback engines or missing symbols.

## 9. Backup, restore and disaster recovery (planned)

The sources set no RPO or RTO yet (see Open questions). This section lists what must be protected and why.

### 9.1 What holds state

| Store | Holds | Loss impact |
|---|---|---|
| Postgres `scan` | queue, scan state, findings, call paths, outbox, priority classes, caps, write-back policies, exports | queued scans lost; bulk query history lost; policies lost |
| Postgres `kb` | vulnerable symbols, fix commits, overrides, library summaries | KB can be rebuilt by symbol-kb-builder **except manual overrides**, which exist only here |
| S3 bucket | result files, input bundles, source tarballs, exports | presigned links and re-sign stop working; bulk query still has the indexed data in Postgres |
| Kafka | in-flight requests and events | the outbox in Postgres re-publishes unsent events |
| Secrets | credentials | re-issue from source systems |

The controller and api are stateless. Jobs are disposable.

### 9.2 Backups

- Postgres: continuous backup with point-in-time recovery from the operator or managed service.
- Export `kb.override`, `scan.priority_class`, `scan.concurrency_limit` and `scan.writeback_policy` regularly as well. They are small and hand-made.
- S3: consider versioning or replication for the bucket. Not decided (see Open questions).
- Keep the deploy manifests and the image manifest in git, so the platform can be rebuilt.

### 9.3 Restore

1. Stop scan-controller (scale to 0) so nothing claims or publishes during restore.
2. Restore Postgres to the chosen point in time.
3. Start scan-controller. The leader resyncs the informer and reconciles every non-terminal scan against live Jobs. Scans whose Jobs are gone retry or fail by the normal rules.
4. The outbox republishes unsent events. Consumers dedupe on scan uid.
5. Emitters re-submit anything sent after the restore point. Idempotency on `metadata.uid` absorbs duplicates that did survive.

### 9.4 Disaster recovery basics

- Loss of the cluster: redeploy from manifests on a new cluster, point at the same Postgres, Kafka and S3. Queue state survives in Postgres.
- Loss of Postgres: restore from backup. If unrecoverable, rebuild `kb` by running symbol-kb-builder, re-apply exported overrides and policies, and ask emitters to re-submit.
- Loss of Kafka: REST intake still works. Unsent events wait in the outbox.
- Loss of DT: scans retry, then fail. No data in reachability-pod is lost.

## 10. Upgrade procedure (planned)

Goal: zero downtime. The api is stateless with 2+ replicas. The controller is leader plus standby. Jobs already running are not touched by a service upgrade.

### 10.1 Database migrations

Schema migrations run with Liquibase (planned, D-031).
- Write migrations as expand, then contract. First add new columns or tables in a backward-compatible way. Remove old ones only in a later release, after no running version uses them.
- New monthly partitions must follow the new table shape.
- Test migrations against a copy of production data before release.

### 10.2 Contract versions

- Kafka topics carry a version suffix (`.v1`). A breaking payload change means a new topic (`.v2`), run in parallel until consumers move.
- The request block `unmapped.reachability` has `schema_version` ("1"), validated against a versioned JSON Schema (D-023). Accept the old and new versions side by side during a migration.
- `summary.json` is produced by the scan Job image and validated by the controller. Upgrade the controller to accept the new `summary.json` version **before** rolling out an image that produces it.
- The REST API is under `/api/v1`. Breaking changes need `/api/v2`.

### 10.3 Rollout order

1. Run expand migrations.
2. Roll out reachability-api (rolling update; keep 2+ replicas ready).
3. Roll out scan-controller. The standby updates first, then the leader. The new leader reconciles non-terminal scans.
4. Roll out the new polyglot image digest, if any (section 6.2).
5. Watch alerts for a day: queue wait, build failures, UNKNOWN rate, DLQ.
6. In a later release, run contract migrations.

Rollback: point the deployments and the image digest back to the previous version. Expand-only migrations make this safe.

## 11. Pre-production verification (planned)

These match the phase 1 exit gate in architecture.md section 14.
- [ ] From a scan Job, egress to any host other than Artifactory and S3 is denied.
- [ ] From a source-fetch Job, egress to any host other than GitHub and S3 is denied.
- [ ] A presigned URL for another S3 bucket fails from `reach-scan`.
- [ ] No ServiceAccount token is mounted in `reach-fetch` or `reach-scan` pods.
- [ ] Metadata endpoint `169.254.169.254` is unreachable from Job pods.
- [ ] Retries work: kill a scan pod (eviction), stop DT briefly, and confirm the scan requeues.
- [ ] Cancel works in FETCHING and SCANNING; it returns 409 in PUBLISHING.
- [ ] End-to-end scans pass for Java, Python, Go and JS/TS in staging.

## 12. Quick reference

- Namespaces: `reach`, `reach-fetch`, `reach-scan`.
- Admin endpoints (role sys-admin): `/api/v1/admin/priority-classes`, `/api/v1/admin/concurrency-limits`, `/api/v1/admin/writeback-policies`, `/api/v1/admin/kb/overrides`, `/api/v1/admin/queue`, `/api/v1/admin/scans/{id}/requeue`.
- Re-sign links: `POST /api/v1/scans/{scanId}/links?ttl=` (role results-reader).
- Cancel: `POST /api/v1/scans/{scanId}/cancel` (role scan-submitter).
- Design: [architecture.md](architecture.md). Decisions: [decisions.md](decisions.md). FAQ: [faq.md](faq.md).

## 13. Open questions

1. Minimum supported OpenShift version below 4.20 is not stated; only "4.20+ preferred" and restricted-v2 until 4.20.
2. Kafka partition counts, replication factor, consumer group name and ACL principal names are not defined.
3. Secret names and secret owners are not defined. Section 5.2 uses placeholders.
4. Rotation periods for the GitHub App private key, IAM user signing keys and the Artifactory minting credential are not set.
5. The Artifactory token-minting identity: which account, what scope it may grant, and how its own credential is stored.
6. Which credential symbol-kb-builder uses for GitHub advisories and fix commits, and whether OSV and SAP Project KB hosts are in the `reach` egress allow list (section 9 of architecture.md lists only DT, S3, Kafka, Artifactory, GitHub API, OIDC).
7. Exact IAM actions for the service role and the signing IAM user, and whether the IAM user also signs the short-lived Job URLs or only result links.
8. Postgres operator or managed service choice, sizing, and the partition management tool.
9. Postgres credentials and Kafka credentials are not in the architecture secrets table.
10. RPO and RTO targets; S3 bucket versioning, replication and encryption settings.
11. Retention of the source tarball (`source.tar.zst`) and the input bundle in S3, if different from results.
12. Alert thresholds and severities. Section 7.2 uses `<threshold>`.
13. ResourceQuota and LimitRange values, and `emptyDir` `sizeLimit` values.
14. Label, taint and node-selector keys for the scanner MachineSet and Job pods.
15. podFailurePolicy action for `DisruptionTarget`: `Ignore` (the Job makes a new pod) or ending the Job so the controller requeues. Architecture.md says "back to QUEUED" and "not counted against the build".
16. Resolved: Liquibase (D-031).
17. Who owns the polyglot image build pipeline, and where the pinned manifest lives.
18. Whether the GitHub App needs any permission beyond Contents read-only (for example, for submodules in other orgs).
19. Which component and image run the source-fetch Job. (The DT key rotation CronJob was dropped by D-032.)
