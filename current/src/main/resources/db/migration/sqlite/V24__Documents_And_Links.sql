-- V24: Documents and links (plan 3.11, AR-99).
--
-- Replaces the 3.x `plan_documents` table with three tables:
--   * documents          - one row per (root, kind, slug); holds the CURRENT body denormalized so reads and
--                          FTS are one table. kind is plan|rule|page (closed set in DocumentKind, no CHECK, so a
--                          new kind needs no table recreation). archived_at replaces a status enum.
--   * document_versions  - every version of a document, v1 included (append-only history).
--   * links              - polymorphic provenance/reference edges. Adoption is no longer a column: a 3.x
--                          adopted document becomes an `adopted_by` link document -> item.
-- and two external-content FTS5 tables over documents(title, body) with their six sync triggers.
--
-- documents carries an EXPLICIT rowid alias (doc_rowid INTEGER PRIMARY KEY): the FTS tables key on it, and
-- VACUUM never renumbers an explicit INTEGER PRIMARY KEY, so the document FTS can never desync after a
-- compaction. id stays the stable BLOB identity (UNIQUE) every caller sees.
--
-- Data rules:
--   * Every plan_documents row is copied with its id, root, body, content_hash and timestamps unchanged, and its
--     rowid copied into doc_rowid. Bodies are copied byte-for-byte: no CRLF normalization and no re-hash (SQL has
--     no sha256); the service normalizes CRLF to LF on the next write, so a CRLF body's hash changes once then.
--   * kind split: a slug starting with exactly 'rule/' (substr, case-sensitive; LIKE would be case-insensitive)
--     becomes kind 'rule' with the prefix stripped; every other slug becomes kind 'plan' unchanged. The two images
--     are disjoint, so the new (root_item_id, kind, slug) unique index cannot collide.
--   * Each copied document gets one document_versions row, version 1.
--   * Each row with a non-null adopted_by_item_id gets one adopted_by link. A row with status 'adopted' and a NULL
--     adopter (its adopter was deleted under ON DELETE SET NULL) migrates as an unadopted document: the owner of
--     that freeze no longer exists.
--   * Link ends carry no FK (a FK cannot target two tables); root_item_id cascades so a root delete clears its
--     documents, versions and links.
--
-- One-way: plan_documents is dropped. Stop every 3.x process before upgrading; the only rollback is a pre-upgrade
-- file backup. Flyway runs with foreign_keys=0 and nothing references plan_documents.

CREATE TABLE documents (
    doc_rowid     INTEGER PRIMARY KEY,
    id            BLOB NOT NULL UNIQUE DEFAULT (randomblob(16)),
    root_item_id  BLOB NOT NULL REFERENCES work_items(id) ON DELETE CASCADE,
    kind          TEXT NOT NULL,
    slug          TEXT NOT NULL,
    title         TEXT,
    body          TEXT NOT NULL,
    content_hash  TEXT NOT NULL,
    version       INTEGER NOT NULL DEFAULT 1,
    archived_at   TIMESTAMP,
    created_at    TIMESTAMP NOT NULL,
    modified_at   TIMESTAMP NOT NULL
);

CREATE UNIQUE INDEX idx_documents_root_kind_slug ON documents(root_item_id, kind, slug);
CREATE INDEX idx_documents_root ON documents(root_item_id);

CREATE TABLE document_versions (
    id            BLOB PRIMARY KEY DEFAULT (randomblob(16)),
    document_id   BLOB NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    version       INTEGER NOT NULL,
    body          TEXT NOT NULL,
    content_hash  TEXT NOT NULL,
    actor_id      TEXT,
    created_at    TIMESTAMP NOT NULL
);

CREATE UNIQUE INDEX idx_document_versions_doc_version ON document_versions(document_id, version);

CREATE TABLE links (
    id            BLOB PRIMARY KEY DEFAULT (randomblob(16)),
    root_item_id  BLOB NOT NULL REFERENCES work_items(id) ON DELETE CASCADE,
    from_kind     TEXT NOT NULL,
    from_id       BLOB NOT NULL,
    to_kind       TEXT NOT NULL,
    to_id         BLOB,
    to_ref        TEXT,
    relation      TEXT NOT NULL,
    anchor        TEXT,
    created_at    TIMESTAMP NOT NULL
);

CREATE INDEX idx_links_from ON links(from_kind, from_id);
CREATE INDEX idx_links_to ON links(to_kind, to_id);
CREATE UNIQUE INDEX idx_links_unique ON links(from_kind, from_id, to_kind, to_id, relation) WHERE to_id IS NOT NULL;

-- ============================================================
-- Re-home every plan_documents row
-- ============================================================

INSERT INTO documents (doc_rowid, id, root_item_id, kind, slug, title, body, content_hash, version, archived_at, created_at, modified_at)
SELECT rowid, id, root_item_id,
       CASE WHEN substr(slug, 1, 5) = 'rule/' THEN 'rule' ELSE 'plan' END,
       CASE WHEN substr(slug, 1, 5) = 'rule/' THEN substr(slug, 6) ELSE slug END,
       NULL, body, content_hash, 1, NULL, created_at, modified_at
FROM plan_documents;

INSERT INTO document_versions (id, document_id, version, body, content_hash, actor_id, created_at)
SELECT randomblob(16), id, 1, body, content_hash, NULL, modified_at
FROM plan_documents;

INSERT INTO links (id, root_item_id, from_kind, from_id, to_kind, to_id, to_ref, relation, anchor, created_at)
SELECT randomblob(16), root_item_id, 'document', id, 'item', adopted_by_item_id, NULL, 'adopted_by', NULL, modified_at
FROM plan_documents
WHERE adopted_by_item_id IS NOT NULL;

DROP TABLE plan_documents;

-- ============================================================
-- Document FTS (external content over documents, keyed on doc_rowid)
-- ============================================================

CREATE VIRTUAL TABLE documents_fts_trigram USING fts5(
    title, body,
    content='documents', content_rowid='doc_rowid',
    tokenize='trigram'
);

CREATE VIRTUAL TABLE documents_fts_text USING fts5(
    title, body,
    content='documents', content_rowid='doc_rowid',
    tokenize='porter unicode61 remove_diacritics 2'
);

INSERT INTO documents_fts_trigram(documents_fts_trigram) VALUES('rebuild');
INSERT INTO documents_fts_text(documents_fts_text) VALUES('rebuild');

CREATE TRIGGER documents_fts_trigram_ai
AFTER INSERT ON documents
BEGIN
    INSERT INTO documents_fts_trigram(rowid, title, body)
    VALUES (new.doc_rowid, new.title, new.body);
END;

CREATE TRIGGER documents_fts_trigram_ad
AFTER DELETE ON documents
BEGIN
    INSERT INTO documents_fts_trigram(documents_fts_trigram, rowid, title, body)
    VALUES ('delete', old.doc_rowid, old.title, old.body);
END;

CREATE TRIGGER documents_fts_trigram_au
AFTER UPDATE OF title, body ON documents
BEGIN
    INSERT INTO documents_fts_trigram(documents_fts_trigram, rowid, title, body)
    VALUES ('delete', old.doc_rowid, old.title, old.body);
    INSERT INTO documents_fts_trigram(rowid, title, body)
    VALUES (new.doc_rowid, new.title, new.body);
END;

CREATE TRIGGER documents_fts_text_ai
AFTER INSERT ON documents
BEGIN
    INSERT INTO documents_fts_text(rowid, title, body)
    VALUES (new.doc_rowid, new.title, new.body);
END;

CREATE TRIGGER documents_fts_text_ad
AFTER DELETE ON documents
BEGIN
    INSERT INTO documents_fts_text(documents_fts_text, rowid, title, body)
    VALUES ('delete', old.doc_rowid, old.title, old.body);
END;

CREATE TRIGGER documents_fts_text_au
AFTER UPDATE OF title, body ON documents
BEGIN
    INSERT INTO documents_fts_text(documents_fts_text, rowid, title, body)
    VALUES ('delete', old.doc_rowid, old.title, old.body);
    INSERT INTO documents_fts_text(rowid, title, body)
    VALUES (new.doc_rowid, new.title, new.body);
END;
