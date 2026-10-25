--liquibase formatted sql

--changeset reach:001-1-schema
CREATE SCHEMA IF NOT EXISTS scan;

--changeset reach:001-2-scan
-- One row per accepted request (architecture.md 6.1). Partitioned monthly by created_at; retention drops partitions (D-025).
CREATE TABLE scan.scan (
    id                 uuid        NOT NULL,
    created_at         timestamptz NOT NULL DEFAULT now(),
    request_uid        text,
    source             text        NOT NULL CHECK (source IN ('REST', 'KAFKA')),
    submitted_by       text,
    org                text        NOT NULL,
    repo               text        NOT NULL,
    repo_url           text        NOT NULL,
    commit_sha         text        NOT NULL CHECK (commit_sha ~ '^[0-9a-f]{40}$'),
    dt_project_name    text        NOT NULL,
    dt_project_version text        NOT NULL,
    dt_project_uuid    uuid,
    priority_class     text        NOT NULL,
    dt_write_back      text        NOT NULL CHECK (dt_write_back IN ('OFF', 'COMMENT', 'VEX', 'ANALYSIS')),
    status             text        NOT NULL CHECK (status IN ('QUEUED', 'FETCHING', 'SCANNING', 'PUBLISHING',
                                                              'SUCCEEDED', 'PARTIAL', 'FAILED', 'CANCELLED')),
    reason             text,
    reason_detail      text,
    attempt            int         NOT NULL DEFAULT 1,
    enqueued_at        timestamptz NOT NULL DEFAULT now(),
    claimed_at         timestamptz,
    finished_at        timestamptz,
    findings_hash      text,
    image_digest       text,
    s3_prefix          text,
    reused_from        uuid,
    options            jsonb       NOT NULL,
    request            jsonb       NOT NULL,
    PRIMARY KEY (id, created_at)
) PARTITION BY RANGE (created_at);

CREATE INDEX scan_active_status_idx ON scan.scan (status)
    WHERE status NOT IN ('SUCCEEDED', 'PARTIAL', 'FAILED', 'CANCELLED');
CREATE INDEX scan_dedupe_idx ON scan.scan (org, repo, commit_sha, findings_hash);
CREATE INDEX scan_dt_project_idx ON scan.scan (dt_project_name, dt_project_version, finished_at);
CREATE INDEX scan_created_idx ON scan.scan (created_at DESC, id DESC);
CREATE INDEX scan_repo_created_idx ON scan.scan (org, repo, created_at DESC);

--changeset reach:001-3-request-key
-- Idempotency on metadata.uid (5.1). Not partitioned; pruned together with the scan partitions.
CREATE TABLE scan.request_key (
    request_uid     text        PRIMARY KEY,
    scan_id         uuid        NOT NULL,
    scan_created_at timestamptz NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX request_key_created_idx ON scan.request_key (created_at);

--changeset reach:001-4-scan-queue
-- A row exists only while the scan is QUEUED (6.1). The controller claims with FOR UPDATE SKIP LOCKED (6.2).
CREATE TABLE scan.scan_queue (
    scan_id         uuid        PRIMARY KEY,
    scan_created_at timestamptz NOT NULL,
    priority_class  text        NOT NULL,
    org             text        NOT NULL,
    repo            text        NOT NULL,
    enqueued_at     timestamptz NOT NULL,
    not_before      timestamptz NOT NULL,
    attempt         int         NOT NULL
);
CREATE INDEX scan_queue_not_before_idx ON scan.scan_queue (not_before);

--changeset reach:001-5-outbox
-- Transactional outbox (D-006). Rows are written in the same transaction as the state change and published by
-- the scan-controller relay.
CREATE TABLE scan.outbox (
    id           bigserial   PRIMARY KEY,
    topic        text        NOT NULL,
    key          text,
    payload      jsonb       NOT NULL,
    headers      jsonb       NOT NULL DEFAULT '{}'::jsonb,
    created_at   timestamptz NOT NULL DEFAULT now(),
    published_at timestamptz
);
CREATE INDEX outbox_unpublished_idx ON scan.outbox (id) WHERE published_at IS NULL;

--changeset reach:001-6-policy-tables
-- Policy tables (D-007, D-004). Edited through the admin API; reloaded on NOTIFY reach_policy.
CREATE TABLE scan.priority_class (
    name          text    PRIMARY KEY,
    weight        int     NOT NULL,
    aging_per_min numeric NOT NULL DEFAULT 1,
    max_running   int,
    enabled       boolean NOT NULL DEFAULT true
);

CREATE TABLE scan.concurrency_limit (
    scope       text NOT NULL CHECK (scope IN ('GLOBAL', 'ORG', 'REPO')),
    key         text NOT NULL,
    max_running int  NOT NULL CHECK (max_running >= 0),
    PRIMARY KEY (scope, key)
);

CREATE TABLE scan.writeback_policy (
    scope      text        NOT NULL CHECK (scope IN ('GLOBAL', 'ORG', 'REPO')),
    key        text        NOT NULL,
    mode       text        NOT NULL CHECK (mode IN ('OFF', 'COMMENT', 'VEX', 'ANALYSIS')),
    verdicts   text[]      NOT NULL DEFAULT ARRAY['NOT_REACHABLE'],
    updated_by text,
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (scope, key)
);

--changeset reach:001-7-policy-notify splitStatements:false
CREATE FUNCTION scan.notify_policy_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM pg_notify('reach_policy', TG_TABLE_NAME);
    RETURN NULL;
END;
$$;

CREATE TRIGGER priority_class_notify AFTER INSERT OR UPDATE OR DELETE ON scan.priority_class
    FOR EACH STATEMENT EXECUTE FUNCTION scan.notify_policy_change();
CREATE TRIGGER concurrency_limit_notify AFTER INSERT OR UPDATE OR DELETE ON scan.concurrency_limit
    FOR EACH STATEMENT EXECUTE FUNCTION scan.notify_policy_change();
CREATE TRIGGER writeback_policy_notify AFTER INSERT OR UPDATE OR DELETE ON scan.writeback_policy
    FOR EACH STATEMENT EXECUTE FUNCTION scan.notify_policy_change();
