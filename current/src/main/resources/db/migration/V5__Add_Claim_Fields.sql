-- V5: Add atomic-claim fields to work_items
-- These four columns support the agent-claim mechanism (issue #117).
-- Time values are stored as ISO-8601 text (SQLite TEXT affinity) to match Flyway/JDBC conventions,
-- consistent with the existing timestamp columns in this table.

ALTER TABLE work_items ADD COLUMN claimed_by TEXT DEFAULT NULL;
ALTER TABLE work_items ADD COLUMN claimed_at TEXT DEFAULT NULL;
ALTER TABLE work_items ADD COLUMN claim_expires_at TEXT DEFAULT NULL;
ALTER TABLE work_items ADD COLUMN original_claimed_at TEXT DEFAULT NULL;

-- Supports fast lookup of "all items held by agent X"
CREATE INDEX idx_work_items_claimed_by ON work_items(claimed_by);

-- Partial index: only rows that are actively claimed; used by expiry-scan queries.
-- SQLite has supported partial indexes since v3.8.0 (2013); confirmed on v3.49.1 bundled via
-- org.xerial:sqlite-jdbc:3.49.1.0.  The query planner uses this index only when the WHERE clause
-- logically implies claimed_by IS NOT NULL, so always include a claimed_by predicate in
-- expiry-scan queries.
CREATE INDEX idx_work_items_claim_expires ON work_items(claim_expires_at)
  WHERE claimed_by IS NOT NULL;
