-- V16: Resource lease history — append-only audit log of lease hold intervals
--
-- Adds `resource_lease_history`, answering "who held resource R at time T" — the audit shape
-- needed to diagnose a foreign-timestamp incident after a lease has already been released or
-- stolen. `resource_leases` (V15) only ever reflects the CURRENT holder (if any); this table
-- accumulates one row per hold INTERVAL and is never pruned in v1 (append-only — no retention
-- policy yet; cardinality tracks lease events, not resource count).
--
-- ## Deliberately NO foreign key on holder_item_id
--
-- Unlike `resource_leases.holder_item_id` (which CASCADEs on work-item delete — the live row has
-- no reason to survive its holder), this table's `holder_item_id` carries NO foreign key
-- constraint to `work_items(id)`, and this is intentional, not an oversight:
--   - The audit trail must remain readable after the holder work item is deleted. A FK with
--     ON DELETE CASCADE would silently erase exactly the history an operator needs after cleanup;
--     ON DELETE SET NULL would lose holder attribution, which is the entire point of this table.
--   - `DirectDatabaseSchemaManagerV16*Test` / the domain model KDoc restate this — keep the Exposed
--     `ResourceLeaseHistoryTable` object in sync (it must also omit the FK) so the two DDL sources
--     do not drift.
--
-- Time values (acquired_at, expires_at, released_at) are ISO-8601 TEXT, written DB-side via
-- datetime('now', ...) — never the JVM clock — matching the V15__Resource_Leases.sql convention.
--
-- ## Interval lifecycle
--
-- One row per hold interval, appended on acquire (fresh or steal-of-expired), updated in place
-- while open (same-holder TTL refresh extends expires_at on the OPEN row — no new row), and closed
-- exactly once (released_at + release_reason set) on release / steal / force-release. See
-- SQLiteResourceLeaseRepository for the write-path details.
--
-- Pure CREATE TABLE — no ALTER COLUMN, no table recreation. Follows the V15__Resource_Leases.sql /
-- V12__Plan_Documents.sql style: BLOB id with a randomblob(16) default.
--
-- Data migration: none. This is a brand-new empty table — pre-existing live leases (rows already
-- in `resource_leases` before this migration ran) simply have no history until their NEXT
-- lifecycle event (next acquire/refresh/release/force-release); this is documented, accepted
-- behavior, not a bug — see SQLiteResourceLeaseRepository's write paths, which self-heal by opening
-- a fresh interval the first time such a lease is touched again.

CREATE TABLE resource_lease_history (
    id                     BLOB PRIMARY KEY DEFAULT (randomblob(16)),
    resource_key           TEXT NOT NULL,
    holder_item_id         BLOB NOT NULL,
    acquired_by_actor_id   TEXT NULL,
    acquired_at            TEXT NOT NULL,
    expires_at             TEXT NOT NULL,
    released_at            TEXT NULL,
    release_reason         TEXT NULL,
    released_by_actor_id   TEXT NULL
);

-- Supports the at-T lookup (findHoldersAt) and recent-activity listing, both filtered/ordered by key + acquired_at.
CREATE INDEX idx_resource_lease_history_key_acquired ON resource_lease_history(resource_key, acquired_at);

-- Supports open-interval scans (released_at IS NULL) used by the acquire/release write paths.
CREATE INDEX idx_resource_lease_history_released_at ON resource_lease_history(released_at);
