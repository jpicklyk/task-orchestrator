-- V15: Resource leases store — server-enforced TTL leases for shared external resources
--
-- Adds `resource_leases`, the storage primitive backing trait-declared resource requirements
-- (see ResourceRequirement / ResourceDefinition in domain/model, and the `resources:` registry
-- in `.taskorchestrator/config.yaml`). This migration builds ONLY the storage + repository layer
-- — no gate enforcement and no MCP tool surface are wired up here (follow-on task).
--
-- Time values (acquired_at, expires_at, original_acquired_at) are stored as ISO-8601 TEXT (SQLite
-- TEXT affinity), matching the V5__Add_Claim_Fields.sql convention for work_items' claim columns
-- (claimed_at / claim_expires_at / original_claimed_at) — the exact same three-timestamp shape,
-- reused here for the lease lifecycle. All lease timestamps are written DB-side via datetime('now',
-- ...) — never the JVM clock — for the same skew-avoidance reason documented on WorkItemRepository.dbNow().
--
-- Pure CREATE TABLE — no ALTER COLUMN, no table recreation. Follows the V12__Plan_Documents.sql
-- style: BLOB id with a randomblob(16) default, a plain (non-inline-UNIQUE) column pair plus a
-- separate CREATE UNIQUE INDEX statement for the uniqueness constraint.
--
-- ## Semaphore-ready design (deliberately NOT unique on resource_key alone)
--
-- v1 enforcement only ever supports a single holder per key (ResourceDefinition.maxHolders is
-- hard-capped to 1 at config-load time — see ResourceDefinition KDoc). Even so, this table's
-- uniqueness constraint is deliberately scoped to the PAIR (resource_key, holder_item_id) rather
-- than resource_key alone:
--   - It lets the SAME item re-acquire (refresh) its own lease on a key it already holds, via an
--     UPSERT keyed on the pair, without first deleting the row.
--   - It leaves room for a future fan-in (maxHolders > 1, "semaphore") enforcement mode to store
--     multiple concurrent-holder rows for the same resource_key without a schema change — only the
--     application-layer holder-count check (`COUNT(*) WHERE resource_key = ? AND expires_at > now`)
--     would need to compare against maxHolders instead of the current hardcoded 1.
--
-- `original_acquired_at` mirrors work_items.original_claimed_at: preserved across same-holder
-- re-acquires (TTL refresh), reset only when a different item acquires the key after the prior
-- lease lapsed.
--
-- budget_limit / budget_used / budget_window_seconds are reserved columns for a future rate-budget
-- enforcement mode (e.g. "N acquisitions per rolling window") — unused by this task's repository
-- surface (acquireAll / releaseAllForItem / forceReleaseByKey / findActive*), always NULL for now.

CREATE TABLE resource_leases (
    id                     BLOB PRIMARY KEY DEFAULT (randomblob(16)),
    resource_key           TEXT NOT NULL,
    holder_item_id         BLOB NOT NULL REFERENCES work_items(id) ON DELETE CASCADE,
    acquired_by_actor_id   TEXT NULL,
    acquired_at            TEXT NOT NULL,
    expires_at             TEXT NOT NULL,
    original_acquired_at   TEXT NOT NULL,
    budget_limit           INTEGER DEFAULT NULL,
    budget_used            INTEGER DEFAULT NULL,
    budget_window_seconds  INTEGER DEFAULT NULL,
    version                INTEGER NOT NULL DEFAULT 0
);

-- Semaphore-ready uniqueness: one row per (resource_key, holder_item_id) pair, NOT per resource_key
-- alone — see the header comment above.
CREATE UNIQUE INDEX idx_resource_leases_key_holder ON resource_leases(resource_key, holder_item_id);

-- Supports the per-key active-holder count check in acquireAll.
CREATE INDEX idx_resource_leases_resource_key ON resource_leases(resource_key);

-- Supports lazy-expiry scans (findAllActive / findActiveByKeys) and future TTL-sweep tooling.
CREATE INDEX idx_resource_leases_expires_at ON resource_leases(expires_at);
