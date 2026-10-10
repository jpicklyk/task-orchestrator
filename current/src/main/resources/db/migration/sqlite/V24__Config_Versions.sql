-- V24: Versioned per-root config (plan section 3.5, U21). Immutable version rows, a head pointer per
-- scope, and the V11 fingerprint history as a side table. Every project_config row is copied to
-- version 1 (source 'migration', same id) and project_config is dropped.
--
-- Pure CREATE + INSERT...SELECT + DROP: no work_items recreation, no trigger, no FTS involvement.
-- canonical_json / model_version stay NULL on the copied rows; the config-canonicalize data step fills
-- them on the first boot (SQL cannot parse YAML). Partial unique indexes carry "one head per scope" and
-- "version unique per scope" because SQLite treats NULLs as distinct in a plain UNIQUE (root_id is NULL
-- for the server scope). created_at / updated_at are the canonical 23-character UTC text form.

CREATE TABLE config_versions (
    id             BLOB    PRIMARY KEY DEFAULT (randomblob(16)),
    scope          TEXT    NOT NULL CHECK (scope IN ('server', 'root')),
    root_id        BLOB    REFERENCES work_items(id) ON DELETE CASCADE,
    version        INTEGER NOT NULL CHECK (version >= 1),
    yaml           TEXT    NOT NULL,
    fingerprint    TEXT    NOT NULL,
    canonical_json TEXT,
    model_version  INTEGER,
    source         TEXT    NOT NULL CHECK (source IN ('push', 'file-import', 'migration', 'rollback', 'reset')),
    principal_id   TEXT,
    req_id         TEXT,
    created_at     TEXT    NOT NULL CHECK (length(created_at) = 23),
    warnings_json  TEXT,
    CHECK ((scope = 'server') = (root_id IS NULL))
);
CREATE UNIQUE INDEX idx_config_versions_root_version   ON config_versions(root_id, version) WHERE scope = 'root';
CREATE UNIQUE INDEX idx_config_versions_server_version ON config_versions(version)          WHERE scope = 'server';
CREATE INDEX        idx_config_versions_root_fingerprint ON config_versions(root_id, fingerprint);

CREATE TABLE config_heads (
    scope      TEXT NOT NULL CHECK (scope IN ('server', 'root')),
    root_id    BLOB REFERENCES work_items(id) ON DELETE CASCADE,
    version_id BLOB NOT NULL REFERENCES config_versions(id),
    updated_at TEXT NOT NULL CHECK (length(updated_at) = 23),
    CHECK ((scope = 'server') = (root_id IS NULL))
);
CREATE UNIQUE INDEX idx_config_heads_root   ON config_heads(root_id) WHERE scope = 'root';
CREATE UNIQUE INDEX idx_config_heads_server ON config_heads(scope)   WHERE scope = 'server';

CREATE TABLE config_known_fingerprints (
    root_id     BLOB NOT NULL REFERENCES work_items(id) ON DELETE CASCADE,
    fingerprint TEXT NOT NULL,
    PRIMARY KEY (root_id, fingerprint)
);

INSERT INTO config_versions (id, scope, root_id, version, yaml, fingerprint, canonical_json, model_version, source, principal_id, req_id, created_at, warnings_json)
SELECT id, 'root', root_item_id, 1, config_yaml, fingerprint, NULL, NULL, 'migration', NULL, NULL, updated_at, NULL FROM project_config;

INSERT INTO config_heads (scope, root_id, version_id, updated_at)
SELECT 'root', root_item_id, id, updated_at FROM project_config;

INSERT OR IGNORE INTO config_known_fingerprints (root_id, fingerprint)
SELECT p.root_item_id, j.value FROM project_config p, json_each(p.fingerprint_history) j
WHERE p.fingerprint_history IS NOT NULL AND json_valid(p.fingerprint_history) AND j.type = 'text';

DROP INDEX IF EXISTS idx_project_config_root_item_id;
DROP TABLE project_config;
