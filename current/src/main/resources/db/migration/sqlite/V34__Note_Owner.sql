-- V34: note owner (plan R21; decision r21-debate). Ownership moves out of the attribution columns: owner_id is the
-- principal id (api:<id> or local) that owns the note, written only by the server from the caller's principal.
-- Nullable with no default, so the ADD COLUMN is schema-only in SQLite (no row rewrite) and every pre-4.0 note reads
-- NULL, which is unowned: 3.x actor ids were self-declared and are never trusted as owners. No index (owner_id is
-- read only with its row). actor_* and verification_* are untouched.
ALTER TABLE notes ADD COLUMN owner_id TEXT;
