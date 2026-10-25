--liquibase formatted sql

--changeset reach:004-1-policy-keys-lower-case
-- GitHub org and repo names are case-insensitive and intake stores them lower-cased (D-033), so the ORG and REPO
-- keys of the policy tables must be lower-case too, or a limit or policy entered as ACME/Payments silently stops
-- matching. Existing keys are lower-cased; where a lower-case row for the same key already exists, it wins and the
-- mixed-case duplicate is dropped. The CHECK constraints keep every later row lower-case.
DELETE FROM scan.concurrency_limit m
 WHERE m.key <> lower(m.key)
   AND EXISTS (SELECT 1 FROM scan.concurrency_limit l WHERE l.scope = m.scope AND l.key = lower(m.key));
UPDATE scan.concurrency_limit SET key = lower(key) WHERE key <> lower(key);
ALTER TABLE scan.concurrency_limit ADD CONSTRAINT concurrency_limit_key_lower CHECK (key = lower(key));

DELETE FROM scan.writeback_policy m
 WHERE m.key <> lower(m.key)
   AND EXISTS (SELECT 1 FROM scan.writeback_policy l WHERE l.scope = m.scope AND l.key = lower(m.key));
UPDATE scan.writeback_policy SET key = lower(key) WHERE key <> lower(key);
ALTER TABLE scan.writeback_policy ADD CONSTRAINT writeback_policy_key_lower CHECK (key = lower(key));
