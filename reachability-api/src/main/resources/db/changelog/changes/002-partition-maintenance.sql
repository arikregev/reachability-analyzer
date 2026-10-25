--liquibase formatted sql

--changeset reach:002-1-ensure-monthly-partitions splitStatements:false
-- Creates the monthly partitions of a table partitioned by created_at, from the current month to months_ahead
-- months ahead. Idempotent and safe to call from several replicas (advisory lock). Returns the partitions created.
CREATE FUNCTION scan.ensure_monthly_partitions(parent regclass, months_ahead int) RETURNS int
LANGUAGE plpgsql AS $$
DECLARE
    month_start date := date_trunc('month', now() AT TIME ZONE 'UTC')::date;
    part_start  date;
    part_name   text;
    parent_name text;
    created     int := 0;
BEGIN
    PERFORM pg_advisory_xact_lock(hashtext('reach.ensure_monthly_partitions'));
    SELECT c.relname INTO parent_name FROM pg_class c WHERE c.oid = parent;
    FOR i IN 0..months_ahead LOOP
        part_start := (month_start + make_interval(months => i))::date;
        part_name := format('%s_p%s', parent_name, to_char(part_start, 'YYYYMM'));
        IF to_regclass(format('scan.%I', part_name)) IS NULL THEN
            EXECUTE format('CREATE TABLE scan.%I PARTITION OF %s FOR VALUES FROM (%L) TO (%L)',
                           part_name, parent,
                           (part_start::timestamp AT TIME ZONE 'UTC'),
                           ((part_start + interval '1 month')::timestamp AT TIME ZONE 'UTC'));
            created := created + 1;
        END IF;
    END LOOP;
    RETURN created;
END;
$$;

--changeset reach:002-2-initial-partitions
SELECT scan.ensure_monthly_partitions('scan.scan', 3);
