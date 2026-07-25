-- V14: Add consumed_credentials audit field to role_transitions
--
-- Adds an optional JSON-array-of-strings column recording which credential/secret *labels*
-- (opaque references such as "vault:prod-db-password" or "github-pat-ci" — never raw secret
-- material) a transition consumed. Populated by the `advance_item` MCP tool's optional
-- `credentialRefs` parameter and the REST advance route's equivalent field; NULL when omitted
-- (the overwhelming majority of transitions). See RoleTransitionsTable.kt / RoleTransition.kt /
-- SQLiteRoleTransitionRepository.kt for the Exposed table, domain model, and JSON
-- serialize/deserialize round-trip.
--
-- Follows the V5__Add_Claim_Fields.sql convention: a simple additive ALTER TABLE ADD COLUMN
-- (SQLite has no ALTER COLUMN; this migration never needs one since the column is new).

ALTER TABLE role_transitions ADD COLUMN consumed_credentials TEXT DEFAULT NULL;
