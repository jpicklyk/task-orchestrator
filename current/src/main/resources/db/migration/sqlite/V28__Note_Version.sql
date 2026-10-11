-- V28: note version (plan section 3.1, Q11). A note's version is a concurrency token and a cache key
-- (`expectedVersion`, `ifVersion`, ETag "v{n}"), not a history: it starts at 1 and goes up by one on every
-- successful upsert of an existing note (written by the note store). A constant, non-NULL default makes the
-- ADD COLUMN a schema-only change in SQLite (no row rewrite); every existing note reads 1.
ALTER TABLE notes ADD COLUMN version INTEGER NOT NULL DEFAULT 1;
