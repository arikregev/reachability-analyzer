--liquibase formatted sql

--changeset reach:003-1-finding
-- One row per DT finding of a scan (architecture.md 6.1). Written by the scan-controller when it loads the
-- findings from Dependency-Track; verdict, reason, level and engine are filled in when the scan is analysed.
-- created_at is the scan's created_at, so both tables share partition boundaries and retention (D-025).
CREATE TABLE scan.finding (
    scan_id            uuid        NOT NULL,
    created_at         timestamptz NOT NULL,
    component_uuid     uuid        NOT NULL,
    vulnerability_uuid uuid        NOT NULL,
    vuln_id            text        NOT NULL,
    vuln_source        text,
    aliases            text[]      NOT NULL DEFAULT '{}',
    severity           text,
    purl               text,
    component_name     text,
    component_version  text,
    module             text,
    verdict            text CHECK (verdict IN ('REACHABLE', 'POTENTIALLY_REACHABLE', 'NOT_REACHABLE', 'UNKNOWN')),
    reason             text,
    level              text CHECK (level IN ('SYMBOL', 'CLASS_OR_PACKAGE', 'IMPORT')),
    engine             text,
    dt_project_name    text        NOT NULL,
    dt_project_version text        NOT NULL,
    repo               text        NOT NULL,
    writeback_status   text,
    PRIMARY KEY (scan_id, component_uuid, vulnerability_uuid, created_at)
) PARTITION BY RANGE (created_at);

CREATE INDEX finding_dt_project_idx ON scan.finding (dt_project_name, dt_project_version, created_at);
CREATE INDEX finding_vuln_id_idx ON scan.finding (vuln_id);
CREATE INDEX finding_aliases_idx ON scan.finding USING gin (aliases);
CREATE INDEX finding_purl_idx ON scan.finding (purl);
CREATE INDEX finding_verdict_idx ON scan.finding (verdict, created_at);

--changeset reach:003-2-finding-partitions
SELECT scan.ensure_monthly_partitions('scan.finding', 3);
