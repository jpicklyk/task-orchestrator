-- V11: Fingerprint history for the fast-forward push guard
--
-- Adds a single NULLABLE column to project_config: fingerprint_history, a JSON array of prior
-- fingerprints for the root (newest first, pruned to 20 entries by the repository). Used to
-- classify an incoming push's fingerprint as CURRENT / SUPERSEDED / UNKNOWN so a stale local
-- config.yaml checkout can be detected and rejected (or force-overridden) instead of silently
-- reverting a later push made from elsewhere.
--
-- Pure ADD COLUMN — SQLite supports this natively, no table recreation required (see
-- V10__Project_Config.sql for the base table). Existing rows get NULL, which the repository
-- treats as "no known ancestors": classifyFingerprint returns UNKNOWN for any non-current
-- fingerprint on a NULL-history row, identical to pre-migration behavior until history
-- accumulates on subsequent upserts. No backfill needed.

ALTER TABLE project_config ADD COLUMN fingerprint_history TEXT;
