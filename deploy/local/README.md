# Local stack

Mock backends for running reachability-api and scan-controller by hand (D-034). Nothing here is for production.

| Service | What it is | Port |
|---|---|---|
| `postgres` | Postgres 18, database `reach`, user and password `reach` | 5432 |
| `kafka` | single-node Apache Kafka 4.1 (KRaft), PLAINTEXT | 9092 |
| `kafka-topics` | one-shot job that creates the topics of architecture.md 5.4 | none |
| `vault` | HashiCorp Vault in dev mode, root token `reach-dev-root` | 8200 |
| `vault-init` | one-shot job that writes the DT key (`secret/reach/dt`, field `api-key-read`) | none |
| `dependency-track` | WireMock serving the Dependency-Track 5.x stubs from `scan-controller/src/test/resources/wiremock/dt` | 8089 |
| `token.sh` | mock identity provider: mints RS256 bearer tokens | none |

The mock Dependency-Track knows these projects (all version `main`):

| Project | Behaviour |
|---|---|
| `acme/payments` | 3 findings over 2 pages (log4j-core, jackson-databind, requests) |
| `acme/empty` | no findings |
| `acme/flaky` | always 503, so the scan is retried with backoff and then fails with `DT_UNAVAILABLE` |
| anything else | 404, so the scan fails with `DT_PROJECT_NOT_FOUND` |

## Start

```bash
docker compose -f deploy/local/docker-compose.yml up -d --wait
```

If Docker Hub is not reachable, override the images, for example
`POSTGRES_IMAGE=mirror.gcr.io/library/postgres:18-alpine KAFKA_IMAGE=mirror.gcr.io/apache/kafka:4.1.0 VAULT_IMAGE=mirror.gcr.io/hashicorp/vault:1.20 WIREMOCK_IMAGE=mirror.gcr.io/wiremock/wiremock:3.13.2`.

## Run the API

The `local` profile points at this stack and checks bearer tokens with the public key from `token.sh`. The key pair is created on first use in `deploy/local/.keys/`, which git ignores.

```bash
export REACH_LOCAL_PUBLIC_KEY=$(deploy/local/token.sh --public-key)
mvn -pl reachability-api -am package -DskipTests
java -Dquarkus.profile=local -jar reachability-api/target/quarkus-app/quarkus-run.jar
```

Liquibase creates the `scan` schema at start-up. The default priority classes are seeded from configuration.

## Run the controller

Start it after the API, because it refuses to start until the schema is migrated. It listens on port 8081, reads the DT key from the local Vault and calls the mock Dependency-Track.

```bash
mvn -pl scan-controller -am package -DskipTests
java -Dquarkus.profile=local -jar scan-controller/target/quarkus-app/quarkus-run.jar
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

With the controller running, a scan for `acme/payments` moves to FETCHING and its three findings appear in `scan.finding`. A scan for an unknown project ends FAILED with `DT_PROJECT_NOT_FOUND`. Every transition is published on `appsec.reachability.status.v1`:

```bash
docker compose -f deploy/local/docker-compose.yml exec -T kafka \
  /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic appsec.reachability.status.v1 --from-beginning
```

Scans stop in FETCHING for now: the fetch and scan Jobs are the next stage.

## Stop

```bash
docker compose -f deploy/local/docker-compose.yml down -v
```
