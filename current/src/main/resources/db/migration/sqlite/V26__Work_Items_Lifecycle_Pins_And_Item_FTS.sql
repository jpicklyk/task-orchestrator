-- V26: The one work_items migration (plan section 3.9; U8c, U13a, U17).
--
-- 1. role becomes nullable (enum CHECK gains the explicit NULL branch) and the table-level relation
--    CHECK ties NULL role to lifecycle 'none'. Six new nullable columns: lifecycle and the pin set.
-- 2. The item FTS tables index description as well as title and summary; the dead prefix='2 3' option
--    is dropped on those two tables only (notes_fts_* untouched).
--
-- DDL only: the 25 existing columns are copied as-is (rowid preserved), the new columns are NULL.
-- Runs on Flyway's foreign_keys = 0 connection, so DROP TABLE work_items does not cascade into the
-- child tables, whose FK clauses bind to the renamed table by name. Never VACUUM here.
-- Rollback: file backup only (table recreation is one-way at the DDL level).

DROP TABLE work_items_fts_trigram;
DROP TABLE work_items_fts_text;

CREATE TABLE work_items_new (
    id                   BLOB PRIMARY KEY DEFAULT (randomblob(16)),
    parent_id            BLOB REFERENCES work_items_new(id),
    title                TEXT NOT NULL,
    description          TEXT,
    summary              TEXT NOT NULL DEFAULT '',
    role                 TEXT DEFAULT 'queue'
                         CHECK (role IS NULL OR role IN ('queue', 'work', 'review', 'blocked', 'terminal')),
    status_label         TEXT,
    previous_role        TEXT CHECK (previous_role IS NULL OR previous_role IN ('queue', 'work', 'review', 'blocked', 'terminal')),
    priority             TEXT NOT NULL DEFAULT 'medium'
                         CHECK (priority IN ('high', 'medium', 'low')),
    complexity           INTEGER,
    requires_verification INTEGER NOT NULL DEFAULT 0,
    depth                INTEGER NOT NULL DEFAULT 0,
    metadata             TEXT,
    tags                 TEXT,
    type                 TEXT,
    properties           TEXT,
    created_at           TIMESTAMP NOT NULL,
    modified_at          TIMESTAMP NOT NULL,
    role_changed_at      TIMESTAMP NOT NULL,
    version              INTEGER NOT NULL DEFAULT 1,
    claimed_by           TEXT DEFAULT NULL,
    claimed_at           TEXT DEFAULT NULL,
    claim_expires_at     TEXT DEFAULT NULL,
    original_claimed_at  TEXT DEFAULT NULL,
    root_id              BLOB,
    lifecycle            TEXT,
    pinned_server_cfg    BLOB REFERENCES config_versions(id),
    pinned_root_cfg      BLOB REFERENCES config_versions(id),
    pinned_schema_key    TEXT,
    pinned_schema_layer  TEXT CHECK (pinned_schema_layer IS NULL OR pinned_schema_layer IN ('server', 'root')),
    pinned_at            TEXT CHECK (pinned_at IS NULL OR length(pinned_at) = 23),
    CHECK ((role IS NULL) = (lifecycle IS NOT NULL AND lifecycle = 'none'))
);

INSERT INTO work_items_new (rowid, id, parent_id, title, description, summary, role, status_label, previous_role, priority, complexity, requires_verification, depth, metadata, tags, type, properties, created_at, modified_at, role_changed_at, version, claimed_by, claimed_at, claim_expires_at, original_claimed_at, root_id)
SELECT rowid, id, parent_id, title, description, summary, role, status_label, previous_role, priority, complexity, requires_verification, depth, metadata, tags, type, properties, created_at, modified_at, role_changed_at, version, claimed_by, claimed_at, claim_expires_at, original_claimed_at, root_id
FROM work_items;

DROP TABLE work_items;

ALTER TABLE work_items_new RENAME TO work_items;

CREATE INDEX idx_work_items_parent        ON work_items(parent_id);
CREATE INDEX idx_work_items_role          ON work_items(role);
CREATE INDEX idx_work_items_depth         ON work_items(depth);
CREATE INDEX idx_work_items_priority      ON work_items(priority);
CREATE INDEX idx_work_items_role_changed  ON work_items(role, role_changed_at);
CREATE INDEX idx_work_items_claimed_by    ON work_items(claimed_by);
CREATE INDEX idx_work_items_claim_expires ON work_items(claim_expires_at);
CREATE INDEX idx_work_items_root_id       ON work_items(root_id);

-- Cycle-detection triggers (V7 bodies verbatim)
CREATE TRIGGER work_items_cycle_check
BEFORE INSERT ON work_items
WHEN NEW.parent_id IS NOT NULL
BEGIN
    -- Self-reference guard: the recursive CTE below cannot catch parent_id = id on
    -- INSERT because the row doesn't exist yet, so the ancestor walk seeds empty.
    SELECT RAISE(ABORT, 'cycle detected: parent_id equals id (self-reference)')
    WHERE NEW.parent_id = NEW.id;

    SELECT RAISE(ABORT, 'cycle detected: parent_id references a descendant of this item')
    WHERE EXISTS (
        WITH RECURSIVE ancestors(node_id) AS (
            SELECT parent_id
            FROM   work_items
            WHERE  id = NEW.parent_id
              AND  parent_id IS NOT NULL
            UNION ALL
            SELECT wi.parent_id
            FROM   work_items wi
            JOIN   ancestors  a  ON wi.id = a.node_id
            WHERE  wi.parent_id IS NOT NULL
        )
        SELECT 1 FROM ancestors WHERE node_id = NEW.id
    );
END;

CREATE TRIGGER work_items_cycle_check_update
BEFORE UPDATE OF parent_id ON work_items
WHEN NEW.parent_id IS NOT NULL
BEGIN
    -- Self-reference guard: catches UPDATE that sets parent_id = id directly.
    SELECT RAISE(ABORT, 'cycle detected: parent_id equals id (self-reference)')
    WHERE NEW.parent_id = NEW.id;

    SELECT RAISE(ABORT, 'cycle detected: parent_id references a descendant of this item')
    WHERE EXISTS (
        WITH RECURSIVE ancestors(node_id) AS (
            SELECT parent_id
            FROM   work_items
            WHERE  id = NEW.parent_id
              AND  parent_id IS NOT NULL
            UNION ALL
            SELECT wi.parent_id
            FROM   work_items wi
            JOIN   ancestors  a  ON wi.id = a.node_id
            WHERE  wi.parent_id IS NOT NULL
        )
        SELECT 1 FROM ancestors WHERE node_id = NEW.id
    );
END;

-- Item FTS (external content): title, summary, description; no prefix index (U17)
CREATE VIRTUAL TABLE work_items_fts_trigram USING fts5(
    title,
    summary,
    description,
    content='work_items',
    content_rowid='rowid',
    tokenize='trigram'
);

CREATE VIRTUAL TABLE work_items_fts_text USING fts5(
    title,
    summary,
    description,
    content='work_items',
    content_rowid='rowid',
    tokenize='porter unicode61 remove_diacritics 2'
);

-- Sync triggers: _au restricted to the three indexed columns (V8 form plus description)
CREATE TRIGGER work_items_fts_trigram_ai
AFTER INSERT ON work_items
BEGIN
    INSERT INTO work_items_fts_trigram(rowid, title, summary, description)
    VALUES (new.rowid, new.title, new.summary, new.description);
END;

CREATE TRIGGER work_items_fts_trigram_ad
AFTER DELETE ON work_items
BEGIN
    INSERT INTO work_items_fts_trigram(work_items_fts_trigram, rowid, title, summary, description)
    VALUES ('delete', old.rowid, old.title, old.summary, old.description);
END;

CREATE TRIGGER work_items_fts_trigram_au
AFTER UPDATE OF title, summary, description ON work_items
BEGIN
    INSERT INTO work_items_fts_trigram(work_items_fts_trigram, rowid, title, summary, description)
    VALUES ('delete', old.rowid, old.title, old.summary, old.description);
    INSERT INTO work_items_fts_trigram(rowid, title, summary, description)
    VALUES (new.rowid, new.title, new.summary, new.description);
END;

CREATE TRIGGER work_items_fts_text_ai
AFTER INSERT ON work_items
BEGIN
    INSERT INTO work_items_fts_text(rowid, title, summary, description)
    VALUES (new.rowid, new.title, new.summary, new.description);
END;

CREATE TRIGGER work_items_fts_text_ad
AFTER DELETE ON work_items
BEGIN
    INSERT INTO work_items_fts_text(work_items_fts_text, rowid, title, summary, description)
    VALUES ('delete', old.rowid, old.title, old.summary, old.description);
END;

CREATE TRIGGER work_items_fts_text_au
AFTER UPDATE OF title, summary, description ON work_items
BEGIN
    INSERT INTO work_items_fts_text(work_items_fts_text, rowid, title, summary, description)
    VALUES ('delete', old.rowid, old.title, old.summary, old.description);
    INSERT INTO work_items_fts_text(rowid, title, summary, description)
    VALUES (new.rowid, new.title, new.summary, new.description);
END;

INSERT INTO work_items_fts_trigram(work_items_fts_trigram) VALUES ('rebuild');
INSERT INTO work_items_fts_text(work_items_fts_text)       VALUES ('rebuild');
