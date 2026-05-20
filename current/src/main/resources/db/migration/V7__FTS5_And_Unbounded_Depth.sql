-- V7: FTS5 full-text search infrastructure and unbounded hierarchy depth
--
-- Components (applied atomically in a single transaction):
--   1. work_items table recreation — establishes clean schema baseline for FTS content
--   2. Cycle-detection BEFORE trigger on parent_id writes
--   3. Four FTS5 external-content virtual tables (trigram + porter tokenizers)
--   4. Twelve sync triggers (4 FTS tables × INSERT + UPDATE + DELETE on source tables)
--   5. Backfill of all four FTS tables via 'rebuild' command
--
-- Rollback note: a down-migration is non-trivial. The FTS virtual tables and their sync
-- triggers can be dropped with DROP TABLE / DROP TRIGGER. However the table recreation
-- in step 1 cannot be trivially reversed via a simple ALTER. Acceptable risk: this
-- migration runs once per database and the transaction guarantees all-or-nothing
-- application.
--
-- SQLite version requirement: >= 3.45 (trigram tokenizer + contentless-delete fixes).
-- Enforced at runtime via org.xerial:sqlite-jdbc:3.49.1.0 (bundles SQLite 3.49.1).

-- ============================================================
-- 1. work_items table recreation
-- ============================================================
-- Recreates work_items to establish a clean baseline compatible with external-content
-- FTS tables. No structural changes to the column set — this preserves all existing rows
-- and recreates all indexes in their V6-final state.

CREATE TABLE work_items_new (
    id                   BLOB PRIMARY KEY DEFAULT (randomblob(16)),
    parent_id            BLOB REFERENCES work_items_new(id),
    title                TEXT NOT NULL,
    description          TEXT,
    summary              TEXT NOT NULL DEFAULT '',
    role                 TEXT NOT NULL DEFAULT 'queue'
                         CHECK (role IN ('queue', 'work', 'review', 'blocked', 'terminal')),
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
    original_claimed_at  TEXT DEFAULT NULL
);

INSERT INTO work_items_new (
    id, parent_id, title, description, summary,
    role, status_label, previous_role, priority,
    complexity, requires_verification, depth,
    metadata, tags, type, properties,
    created_at, modified_at, role_changed_at, version,
    claimed_by, claimed_at, claim_expires_at, original_claimed_at
)
SELECT
    id, parent_id, title, description, summary,
    role, status_label, previous_role, priority,
    complexity, requires_verification, depth,
    metadata, tags, type, properties,
    created_at, modified_at, role_changed_at, version,
    claimed_by, claimed_at, claim_expires_at, original_claimed_at
FROM work_items;

DROP TABLE work_items;

ALTER TABLE work_items_new RENAME TO work_items;

-- Recreate all indexes from V6-final state (V1 base + V5 claim indexes + V6 reshape)
CREATE INDEX idx_work_items_parent        ON work_items(parent_id);
CREATE INDEX idx_work_items_role          ON work_items(role);
CREATE INDEX idx_work_items_depth         ON work_items(depth);
CREATE INDEX idx_work_items_priority      ON work_items(priority);
CREATE INDEX idx_work_items_role_changed  ON work_items(role, role_changed_at);
CREATE INDEX idx_work_items_claimed_by    ON work_items(claimed_by);
CREATE INDEX idx_work_items_claim_expires ON work_items(claim_expires_at);

-- ============================================================
-- 2. Cycle-detection trigger
-- ============================================================
-- Fires BEFORE any INSERT or UPDATE that sets parent_id.
-- Uses a recursive CTE to walk up the ancestor chain from the candidate parent.
-- If the item being written appears anywhere in that ancestor chain, the write
-- would create a cycle and is aborted with RAISE(ABORT, ...).

CREATE TRIGGER work_items_cycle_check
BEFORE INSERT ON work_items
WHEN NEW.parent_id IS NOT NULL
BEGIN
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

-- ============================================================
-- 3. FTS5 virtual tables (external-content mode)
-- ============================================================
-- External-content mode: FTS index does NOT store copies of the source data;
-- content= points to the backing table, content_rowid= to its rowid column.
-- Sync triggers (below) keep the FTS index in sync with mutations on source tables.
-- prefix='2 3' enables prefix queries of length 2 and 3.

-- work_items: trigram tokenizer (substring search, case-insensitive)
CREATE VIRTUAL TABLE work_items_fts_trigram USING fts5(
    title,
    summary,
    content='work_items',
    content_rowid='rowid',
    tokenize='trigram',
    prefix='2 3'
);

-- work_items: porter stemmer + unicode61 (word-boundary / stemmed search)
CREATE VIRTUAL TABLE work_items_fts_text USING fts5(
    title,
    summary,
    content='work_items',
    content_rowid='rowid',
    tokenize='porter unicode61 remove_diacritics 2',
    prefix='2 3'
);

-- notes: trigram tokenizer
CREATE VIRTUAL TABLE notes_fts_trigram USING fts5(
    body,
    content='notes',
    content_rowid='rowid',
    tokenize='trigram',
    prefix='2 3'
);

-- notes: porter stemmer + unicode61
CREATE VIRTUAL TABLE notes_fts_text USING fts5(
    body,
    content='notes',
    content_rowid='rowid',
    tokenize='porter unicode61 remove_diacritics 2',
    prefix='2 3'
);

-- ============================================================
-- 4. Sync triggers (4 FTS tables × INSERT + UPDATE + DELETE)
-- ============================================================
-- External-content FTS tables require manual sync triggers.
-- DELETE uses the sentinel 'delete' command to mark rows for removal.
-- UPDATE = DELETE old values, then INSERT new values.

-- --- work_items_fts_trigram ---

CREATE TRIGGER work_items_fts_trigram_ai
AFTER INSERT ON work_items
BEGIN
    INSERT INTO work_items_fts_trigram(rowid, title, summary)
    VALUES (new.rowid, new.title, new.summary);
END;

CREATE TRIGGER work_items_fts_trigram_ad
AFTER DELETE ON work_items
BEGIN
    INSERT INTO work_items_fts_trigram(work_items_fts_trigram, rowid, title, summary)
    VALUES ('delete', old.rowid, old.title, old.summary);
END;

CREATE TRIGGER work_items_fts_trigram_au
AFTER UPDATE ON work_items
BEGIN
    INSERT INTO work_items_fts_trigram(work_items_fts_trigram, rowid, title, summary)
    VALUES ('delete', old.rowid, old.title, old.summary);
    INSERT INTO work_items_fts_trigram(rowid, title, summary)
    VALUES (new.rowid, new.title, new.summary);
END;

-- --- work_items_fts_text ---

CREATE TRIGGER work_items_fts_text_ai
AFTER INSERT ON work_items
BEGIN
    INSERT INTO work_items_fts_text(rowid, title, summary)
    VALUES (new.rowid, new.title, new.summary);
END;

CREATE TRIGGER work_items_fts_text_ad
AFTER DELETE ON work_items
BEGIN
    INSERT INTO work_items_fts_text(work_items_fts_text, rowid, title, summary)
    VALUES ('delete', old.rowid, old.title, old.summary);
END;

CREATE TRIGGER work_items_fts_text_au
AFTER UPDATE ON work_items
BEGIN
    INSERT INTO work_items_fts_text(work_items_fts_text, rowid, title, summary)
    VALUES ('delete', old.rowid, old.title, old.summary);
    INSERT INTO work_items_fts_text(rowid, title, summary)
    VALUES (new.rowid, new.title, new.summary);
END;

-- --- notes_fts_trigram ---

CREATE TRIGGER notes_fts_trigram_ai
AFTER INSERT ON notes
BEGIN
    INSERT INTO notes_fts_trigram(rowid, body)
    VALUES (new.rowid, new.body);
END;

CREATE TRIGGER notes_fts_trigram_ad
AFTER DELETE ON notes
BEGIN
    INSERT INTO notes_fts_trigram(notes_fts_trigram, rowid, body)
    VALUES ('delete', old.rowid, old.body);
END;

CREATE TRIGGER notes_fts_trigram_au
AFTER UPDATE ON notes
BEGIN
    INSERT INTO notes_fts_trigram(notes_fts_trigram, rowid, body)
    VALUES ('delete', old.rowid, old.body);
    INSERT INTO notes_fts_trigram(rowid, body)
    VALUES (new.rowid, new.body);
END;

-- --- notes_fts_text ---

CREATE TRIGGER notes_fts_text_ai
AFTER INSERT ON notes
BEGIN
    INSERT INTO notes_fts_text(rowid, body)
    VALUES (new.rowid, new.body);
END;

CREATE TRIGGER notes_fts_text_ad
AFTER DELETE ON notes
BEGIN
    INSERT INTO notes_fts_text(notes_fts_text, rowid, body)
    VALUES ('delete', old.rowid, old.body);
END;

CREATE TRIGGER notes_fts_text_au
AFTER UPDATE ON notes
BEGIN
    INSERT INTO notes_fts_text(notes_fts_text, rowid, body)
    VALUES ('delete', old.rowid, old.body);
    INSERT INTO notes_fts_text(rowid, body)
    VALUES (new.rowid, new.body);
END;

-- ============================================================
-- 5. FTS backfill
-- ============================================================
-- Rebuilds the FTS index from the current content of the backing tables.
-- Safe to run on an empty database (produces an empty index).
-- Idempotent: can be re-run after a failed migration attempt.

INSERT INTO work_items_fts_trigram(work_items_fts_trigram) VALUES ('rebuild');
INSERT INTO work_items_fts_text(work_items_fts_text)       VALUES ('rebuild');
INSERT INTO notes_fts_trigram(notes_fts_trigram)           VALUES ('rebuild');
INSERT INTO notes_fts_text(notes_fts_text)                 VALUES ('rebuild');
