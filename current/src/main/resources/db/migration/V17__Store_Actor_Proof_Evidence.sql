-- V17: Store actor-proof evidence (hash + verified claims) instead of raw JWTs; scrub existing rows
--
-- `notes.actor_proof` / `role_transitions.actor_proof` (V4) held the raw bearer JWT, which replays as
-- VERIFIED until its own `exp` from anyone who can read the DB or a backup. From this version the
-- application persists only evidence: a SHA-256 of the proof (whenever a non-blank proof was supplied)
-- and the verified JWT claims as JSON (only on a VERIFIED outcome). MCP item 983615e7.
--
-- `actor_proof` is kept (DROP COLUMN would force a table recreation against the notes FTS5 triggers,
-- see V7/V8) and is always written as NULL by the repositories from now on. `PRAGMA secure_delete`
-- makes SQLite zero the freed cell content instead of leaving it in a free page; VACUUM is
-- deliberately NOT used — it cannot run inside Flyway's transaction and can renumber the implicit
-- rowids the FTS5 indexes bind to (`content_rowid='rowid'` on BLOB-keyed tables).
--
-- Historical rows get no hash/claims (no SQLite SHA-256; a Java-side migration was rejected).
-- `modified_at` is untouched, and the FTS triggers (`AFTER UPDATE OF body`, V8) do not fire.
-- Release note: pre-upgrade backups still hold live tokens until each token's `exp`.
-- Full rationale: current/docs/fleet-deployment.md ("Proof handling").

ALTER TABLE notes ADD COLUMN actor_proof_sha256 TEXT DEFAULT NULL;
ALTER TABLE notes ADD COLUMN actor_proof_claims TEXT DEFAULT NULL;

ALTER TABLE role_transitions ADD COLUMN actor_proof_sha256 TEXT DEFAULT NULL;
ALTER TABLE role_transitions ADD COLUMN actor_proof_claims TEXT DEFAULT NULL;

PRAGMA secure_delete = ON;

UPDATE notes SET actor_proof = NULL WHERE actor_proof IS NOT NULL;
UPDATE role_transitions SET actor_proof = NULL WHERE actor_proof IS NOT NULL;
