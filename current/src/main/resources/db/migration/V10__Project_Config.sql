-- V10: Per-root project configuration storage
--
-- Adds `project_config`, a one-row-per-root table holding a raw YAML config document scoped to a
-- single project root (a depth-0 WorkItem, identified by root_item_id). This is the storage layer
-- for per-root schema/trait overrides (T3.2 resolveSchema integration and T3.3 MCP tool surface
-- build on top of this table via PerRootConfigService — neither is touched by this migration).
--
-- Pure CREATE TABLE — no ALTER COLUMN, no table recreation. Follows the exact conventions of the
-- existing single-parent-FK tables (see notes / dependencies / role_transitions above): BLOB id
-- with a randomblob(16) default, a plain (non-inline-UNIQUE) column plus a separate
-- CREATE UNIQUE INDEX statement for the uniqueness constraint, and `ON DELETE CASCADE` on the FK
-- to work_items(id) — enforced because this project turns on `PRAGMA foreign_keys = ON` at
-- connection time (see DatabaseManager.initialize), unlike the root_id column added in V9 which
-- deliberately omits a FK (self-reference at insert time, not applicable here).
--
-- One row per root: root_item_id is NOT NULL and unique — a root can have at most one
-- project_config row. Upserts (via PerRootConfigService / ProjectConfigRepository) replace the
-- existing row's config_yaml/fingerprint/updated_at rather than inserting a second row.

CREATE TABLE project_config (
    id              BLOB PRIMARY KEY DEFAULT (randomblob(16)),
    root_item_id    BLOB NOT NULL REFERENCES work_items(id) ON DELETE CASCADE,
    config_yaml     TEXT NOT NULL,
    fingerprint     TEXT NOT NULL,
    updated_at      TIMESTAMP NOT NULL
);

CREATE UNIQUE INDEX idx_project_config_root_item_id ON project_config(root_item_id);
