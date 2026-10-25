# Local stack

Mock backends for running reachability-api and scan-controller by hand (D-034). Nothing here is for production. Every port is published on 127.0.0.1 only, since the credentials are fixed and Kafka is plaintext (D-041).

| Service | What it is | Port |
|---|---|---|
| `postgres` | Postgres 18, database `reach`, user and password `reach` | 5432 |
| `kafka` | single-node Apache Kafka 4.1 (KRaft), PLAINTEXT | 9092 |
| `kafka-topics` | one-shot job that creates the topics of architecture.md 5.4 | none |
| `vault` | HashiCorp Vault in dev mode, root token `reach-dev-root` | 8200 |
| `vault-init` | one-shot job that writes the DT key (`secret/reach/dt`, field `api-key-read`) and the GitHub App key (`secret/reach/github`, field `private-key`, the `token.sh` key) | none |
| `dependency-track` | WireMock serving the Dependency-Track 5.x stubs from `scan-controller/src/test/resources/wiremock/dt` | 8089 |
| `github` | WireMock serving the GitHub REST stubs from `scan-controller/src/test/resources/wiremock/github` | 8091 |
| `s3` | adobe/s3mock with bucket `reach-local`; any access key works | 9090 |
| `token.sh` | mock identity provider: mints RS256 bearer tokens | none |

The mock Dependency-Track knows these projects (all version `main`):

| Project | Behaviour |
|---|---|
| `acme/payments` | 3 findings over 2 pages (log4j-core, jackson-databind, requests); bound to `github.com/acme/payments` by a `vcs` external reference (D-037) |
| `acme/empty` | no findings; names no repository, so VEX or ANALYSIS write-back is lowered to COMMENT (D-037) |
| `acme/other` | bound to `github.com/acme/billing` by a `reach-repo=` tag, so a scan of any other repo fails with `DT_REPO_MISMATCH` (D-037) |
| `acme/not-installed`, `acme/github-down` | the payments findings, no repository named; paired with the mock GitHub's failing repos of the same names |
| `acme/flaky` | always 503, so the scan is retried with backoff and then fails with `DT_UNAVAILABLE` |
| `acme/broken` | answers the lookup without a uuid, so it is retried like an outage (`DT_UNAVAILABLE`) |
| anything else | 404, so the scan fails with `DT_PROJECT_NOT_FOUND` |

The mock GitHub has the App installed on every `acme/*` repo, and every commit exists, with these exceptions:

| Repo or commit | Behaviour |
|---|---|
| `acme/not-installed` | 404 on the installation, so the scan fails with `COMMIT_NOT_FOUND` |
| `acme/github-down` | 503, so the scan is retried and then fails with `GITHUB_UNAVAILABLE` |
| commit `0000000000000000000000000000000000000000` | 422, so the scan fails with `COMMIT_NOT_FOUND` |

## Start

Create the local key pair first: `vault-init` stores it as the GitHub App key, and the API checks tokens with it.

```bash
deploy/local/token.sh --public-key >/dev/null
docker compose -f deploy/local/docker-compose.yml up -d --wait
```

If Docker Hub is not reachable, override the images, for example
`POSTGRES_IMAGE=mirror.gcr.io/library/postgres:18-alpine KAFKA_IMAGE=mirror.gcr.io/apache/kafka:4.1.0 VAULT_IMAGE=mirror.gcr.io/hashicorp/vault:1.20 WIREMOCK_IMAGE=mirror.gcr.io/wiremock/wiremock:3.13.2 S3MOCK_IMAGE=mirror.gcr.io/adobe/s3mock:5.2.3`.

## Run the API

The `local` profile points at this stack and checks bearer tokens with the public key from `token.sh`. The key pair is created on first use in `deploy/local/.keys/`, which git ignores.

```bash
export REACH_LOCAL_PUBLIC_KEY=$(deploy/local/token.sh --public-key)
mvn -pl reachability-api -am package -DskipTests
java -Dquarkus.profile=local -jar reachability-api/target/quarkus-app/quarkus-run.jar
```

Liquibase creates the `scan` schema at start-up. The default priority classes are seeded from configuration.

## Run the controller

Start it after the API, because it refuses to start until the schema is migrated. It listens on port 8081, reads the DT and GitHub App keys from the local Vault, and calls the mock Dependency-Track, GitHub and S3. There is no cluster locally, so `reach.job.enabled` is false and no Job is created.

```bash
mvn -pl scan-controller -am package -DskipTests
java -Dquarkus.profile=local -Daws.accessKeyId=local -Daws.secretAccessKey=local \
  -jar scan-controller/target/quarkus-app/quarkus-run.jar
```

## Try it

```bash
# A token with the roles you want (default: scan-submitter results-reader)
TOKEN=$(deploy/local/token.sh scan-submitter results-reader reach-priority-pr)

# Submit by REST: 202 with scanId and Location
curl -i -X POST localhost:8080/api/v1/scans -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' --data @docs/contracts/scan-request.v1.example.json

# Submit by Kafka (one JSON document per line)
jq -c '.metadata.uid = "kafka-local-1"' docs/contracts/scan-request.v1.example.json | \
  docker compose -f deploy/local/docker-compose.yml exec -T kafka \
  /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server localhost:9092 --topic appsec.reachability.requests.v1

# List scans
curl -s "localhost:8080/api/v1/scans?repo=acme/payments" -H "Authorization: Bearer $TOKEN"

# Invalid records land on the DLQ with reach-error-* headers
docker compose -f deploy/local/docker-compose.yml exec -T kafka \
  /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic appsec.reachability.requests.dlq.v1 --from-beginning --property print.headers=true

# Look at the queue and the outbox
docker compose -f deploy/local/docker-compose.yml exec postgres \
  psql -U reach -c 'SELECT scan_id, priority_class, not_before FROM scan.scan_queue' \
  -c 'SELECT id, topic, key FROM scan.outbox'
```

With the controller running, a scan for `acme/payments` moves to FETCHING, its three findings appear in `scan.finding`, the input bundle is written to the mock S3, and a GitHub installation token is minted from the mock GitHub. A scan for `acme/empty` moves to PUBLISHING because it has nothing to analyse. A scan for an unknown project ends FAILED with `DT_PROJECT_NOT_FOUND`. Every transition is published on `appsec.reachability.status.v1`:

```bash
docker compose -f deploy/local/docker-compose.yml exec -T kafka \
  /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic appsec.reachability.status.v1 --from-beginning
```

```bash
# The input bundle in the mock S3, and the calls the mock GitHub received
curl -s 'localhost:9090/reach-local?list-type=2'
curl -s localhost:8091/__admin/requests | jq '.requests[].request.url'
```

Scans stop in FETCHING locally, because the fetch and scan Jobs need a cluster. The Job flow is covered by `JobsTest` against the Kubernetes mock server.

## Stop

```bash
docker compose -f deploy/local/docker-compose.yml down -v
```
