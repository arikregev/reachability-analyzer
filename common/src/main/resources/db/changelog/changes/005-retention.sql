--liquibase formatted sql

--changeset reach:005-1-apply-retention splitStatements:false
-- Postgres retention (D-025, D-038). Drops the monthly partitions of the scan tables whose whole month is older than
-- the cutoff, after removing the rows that point into them: queue rows and idempotency keys. A partition goes only
-- when its upper bound is at or before the cutoff, so nothing younger than the retention period is lost. Child tables
-- go first (call_path once it exists, then finding) so no row outlives its scan. Idempotent and safe to call from
-- several replicas (advisory lock). Returns the number of partitions dropped.
CREATE FUNCTION scan.apply_retention(keep interval) RETURNS int
LANGUAGE plpgsql AS $$
DECLARE
    cutoff  timestamptz := now() - keep;
    parent  text;
    part    record;
    dropped int := 0;
BEGIN
    IF keep < interval '1 day' THEN
        RAISE EXCEPTION 'retention % is too short', keep;
    END IF;
    PERFORM pg_advisory_xact_lock(hashtext('reach.apply_retention'));
    DELETE FROM scan.scan_queue WHERE scan_created_at < cutoff;
    DELETE FROM scan.request_key WHERE scan_created_at < cutoff;
    FOREACH parent IN ARRAY ARRAY['call_path', 'finding', 'scan'] LOOP
        FOR part IN
            SELECT c.relname,
                   (to_date(right(c.relname, 6), 'YYYYMM') + interval '1 month')::timestamp AT TIME ZONE 'UTC' AS upper_bound
              FROM pg_inherits i
              JOIN pg_class c ON c.oid = i.inhrelid
              JOIN pg_class p ON p.oid = i.inhparent
              JOIN pg_namespace n ON n.oid = p.relnamespace
             WHERE n.nspname = 'scan' AND p.relname = parent AND c.relname ~ ('^' || parent || '_p[0-9]{6}$')
             ORDER BY c.relname
        LOOP
            IF part.upper_bound <= cutoff THEN
                EXECUTE format('DROP TABLE scan.%I', part.relname);
                dropped := dropped + 1;
            END IF;
        END LOOP;
    END LOOP;
    RETURN dropped;
END;
$$;
