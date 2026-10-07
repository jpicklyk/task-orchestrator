-- V8: Restrict FTS update triggers to content-bearing columns only
--
-- Problem: V7 created AFTER UPDATE ON work_items (and notes) FTS sync triggers with no
-- column filter. Every UPDATE to any column — including claim fields (claimed_by,
-- claim_expires_at), version bumps, and role changes — fires a full FTS delete+reinsert
-- across both FTS tables. This is needless write amplification: those columns are not
-- indexed by FTS and their values do not affect search results.
--
-- Fix: Drop the four _au triggers and recreate them with an OF column-list so they only
-- fire when the indexed content actually changes:
--   work_items: AFTER UPDATE OF title, summary
--   notes:      AFTER UPDATE OF body
--
-- No data migration is needed. Existing FTS index content is correct and unaffected.
-- Each DROP TRIGGER IF EXISTS makes the migration idempotent-safe.

-- ============================================================
-- work_items FTS update triggers — restricted to title, summary
-- ============================================================

DROP TRIGGER IF EXISTS work_items_fts_trigram_au;
CREATE TRIGGER work_items_fts_trigram_au
AFTER UPDATE OF title, summary ON work_items
BEGIN
    INSERT INTO work_items_fts_trigram(work_items_fts_trigram, rowid, title, summary)
    VALUES ('delete', old.rowid, old.title, old.summary);
    INSERT INTO work_items_fts_trigram(rowid, title, summary)
    VALUES (new.rowid, new.title, new.summary);
END;

DROP TRIGGER IF EXISTS work_items_fts_text_au;
CREATE TRIGGER work_items_fts_text_au
AFTER UPDATE OF title, summary ON work_items
BEGIN
    INSERT INTO work_items_fts_text(work_items_fts_text, rowid, title, summary)
    VALUES ('delete', old.rowid, old.title, old.summary);
    INSERT INTO work_items_fts_text(rowid, title, summary)
    VALUES (new.rowid, new.title, new.summary);
END;

-- ============================================================
-- notes FTS update triggers — restricted to body
-- ============================================================

DROP TRIGGER IF EXISTS notes_fts_trigram_au;
CREATE TRIGGER notes_fts_trigram_au
AFTER UPDATE OF body ON notes
BEGIN
    INSERT INTO notes_fts_trigram(notes_fts_trigram, rowid, body)
    VALUES ('delete', old.rowid, old.body);
    INSERT INTO notes_fts_trigram(rowid, body)
    VALUES (new.rowid, new.body);
END;

DROP TRIGGER IF EXISTS notes_fts_text_au;
CREATE TRIGGER notes_fts_text_au
AFTER UPDATE OF body ON notes
BEGIN
    INSERT INTO notes_fts_text(notes_fts_text, rowid, body)
    VALUES ('delete', old.rowid, old.body);
    INSERT INTO notes_fts_text(rowid, body)
    VALUES (new.rowid, new.body);
END;
