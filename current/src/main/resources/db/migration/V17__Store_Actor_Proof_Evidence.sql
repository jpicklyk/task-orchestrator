-- V17: Store actor-proof evidence (hash + verified claims) instead of raw JWTs; scrub existing rows
--
-- `notes.actor_proof` / `role_transitions.actor_proof` (added V4) stored the raw bearer JWT
-- verbatim. A token stored this way replays as VERIFIED until its own `exp`, from anyone who can
-- read the DB (an ADMIN REST caller via `?include=proof`, or a DB file / backup copy) — the
-- credential itself, not evidence about it. See MCP item 983615e7 diagnosis.
--
-- This migration:
--   1. Adds two nullable evidence columns per table: a SHA-256 hash of the proof (set whenever a
--      non-blank proof was supplied, independent of verification outcome) and the verified JWT
--      claims as JSON (set only when the proof was cryptographically VERIFIED).
--   2. Scrubs every existing `actor_proof` value to NULL, with `PRAGMA secure_delete = ON` first
--      so SQLite zeroes the freed cell/overflow content rather than leaving it in a free page.
--
-- `actor_proof` itself is NOT dropped — SQLite has no DROP COLUMN compatible with the notes
-- table's FTS5 external-content triggers without a full table recreation (see V7/V8), and every
-- future write now always sets it to NULL at the application layer (SQLiteNoteRepository /
-- SQLiteRoleTransitionRepository), so the column becomes permanently unused rather than removed.
--
-- Historical rows get no hash and no claims — SQLite has no built-in SHA-256, and computing one
-- via a Java-side migration was rejected (adds a code-execution migration path for what should
-- stay pure SQL). This is a known, accepted gap for rows written before this migration ran.
--
-- `modified_at` is NOT touched by the scrub UPDATEs, so ETags and SSE stay stable. The notes FTS5
-- update triggers are restricted to `AFTER UPDATE OF body` (see V8), so the scrub UPDATE (which
-- only touches actor_proof) does not fire them.
--
-- No VACUUM: it cannot run inside a transaction (Flyway wraps each SQL migration in one), and it
-- can renumber rowids of tables without an INTEGER PRIMARY KEY — notes/work_items use BLOB PKs
-- while the FTS5 indexes bind `content_rowid='rowid'`, so a VACUUM would desync search. See
-- migration-assessment note on MCP item 983615e7 for the full analysis.
--
-- Release note: pre-upgrade backups (docker cp, volume snapshots) still hold live tokens until
-- each token's own exp — rotate long-lived actor keys/tokens and purge old backups after upgrading.

ALTER TABLE notes ADD COLUMN actor_proof_sha256 TEXT DEFAULT NULL;
ALTER TABLE notes ADD COLUMN actor_proof_claims TEXT DEFAULT NULL;

ALTER TABLE role_transitions ADD COLUMN actor_proof_sha256 TEXT DEFAULT NULL;
ALTER TABLE role_transitions ADD COLUMN actor_proof_claims TEXT DEFAULT NULL;

PRAGMA secure_delete = ON;

UPDATE notes SET actor_proof = NULL WHERE actor_proof IS NOT NULL;
UPDATE role_transitions SET actor_proof = NULL WHERE actor_proof IS NOT NULL;
