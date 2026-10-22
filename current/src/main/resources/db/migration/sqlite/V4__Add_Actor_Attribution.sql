-- Actor attribution columns on role_transitions
ALTER TABLE role_transitions ADD COLUMN actor_id TEXT;
ALTER TABLE role_transitions ADD COLUMN actor_kind TEXT;
ALTER TABLE role_transitions ADD COLUMN actor_parent TEXT;
ALTER TABLE role_transitions ADD COLUMN actor_proof TEXT;
ALTER TABLE role_transitions ADD COLUMN verification_status TEXT;
ALTER TABLE role_transitions ADD COLUMN verification_verifier TEXT;
ALTER TABLE role_transitions ADD COLUMN verification_reason TEXT;

-- Actor attribution columns on notes
ALTER TABLE notes ADD COLUMN actor_id TEXT;
ALTER TABLE notes ADD COLUMN actor_kind TEXT;
ALTER TABLE notes ADD COLUMN actor_parent TEXT;
ALTER TABLE notes ADD COLUMN actor_proof TEXT;
ALTER TABLE notes ADD COLUMN verification_status TEXT;
ALTER TABLE notes ADD COLUMN verification_verifier TEXT;
ALTER TABLE notes ADD COLUMN verification_reason TEXT;
