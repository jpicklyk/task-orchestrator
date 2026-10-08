-- V18: Normalize every persisted timestamp to one canonical UTC text shape.
--
-- Canonical form: 'YYYY-MM-DD HH:MM:SS.SSS' (UTC, fixed 23 characters, millisecond truncation) -
-- the same text UtcTimestampColumnType writes. Before this migration two shapes coexisted:
-- Exposed-written values carried '.SSS', while SQLite datetime('now')-written claim and lease
-- values did not, so text comparison inside one second was wrong. Data-only migration: no column,
-- index, trigger or table changes (the UPDATEs touch none of the columns the UPDATE triggers watch).
--
-- Idempotent: only rows whose text differs from its canonical form are rewritten, so already
-- canonical rows (and a rerun) change nothing. NULLs stay NULL, a value strftime cannot parse
-- (or a non-text value) is left untouched. Offset suffixes (Z, +-HH:MM) are converted to UTC.
-- Unrecoverable: values written as local wall-clock by a non-UTC JVM before the UTC guard carry
-- no offset and are treated as UTC.

UPDATE work_items SET created_at = strftime('%Y-%m-%d %H:%M:%f', created_at)
 WHERE created_at IS NOT NULL AND typeof(created_at) = 'text'
   AND strftime('%Y-%m-%d %H:%M:%f', created_at) IS NOT NULL
   AND created_at <> strftime('%Y-%m-%d %H:%M:%f', created_at);

UPDATE work_items SET modified_at = strftime('%Y-%m-%d %H:%M:%f', modified_at)
 WHERE modified_at IS NOT NULL AND typeof(modified_at) = 'text'
   AND strftime('%Y-%m-%d %H:%M:%f', modified_at) IS NOT NULL
   AND modified_at <> strftime('%Y-%m-%d %H:%M:%f', modified_at);

UPDATE work_items SET role_changed_at = strftime('%Y-%m-%d %H:%M:%f', role_changed_at)
 WHERE role_changed_at IS NOT NULL AND typeof(role_changed_at) = 'text'
   AND strftime('%Y-%m-%d %H:%M:%f', role_changed_at) IS NOT NULL
   AND role_changed_at <> strftime('%Y-%m-%d %H:%M:%f', role_changed_at);

UPDATE work_items SET claimed_at = strftime('%Y-%m-%d %H:%M:%f', claimed_at)
 WHERE claimed_at IS NOT NULL AND typeof(claimed_at) = 'text'
   AND strftime('%Y-%m-%d %H:%M:%f', claimed_at) IS NOT NULL
   AND claimed_at <> strftime('%Y-%m-%d %H:%M:%f', claimed_at);

UPDATE work_items SET claim_expires_at = strftime('%Y-%m-%d %H:%M:%f', claim_expires_at)
 WHERE claim_expires_at IS NOT NULL AND typeof(claim_expires_at) = 'text'
   AND strftime('%Y-%m-%d %H:%M:%f', claim_expires_at) IS NOT NULL
   AND claim_expires_at <> strftime('%Y-%m-%d %H:%M:%f', claim_expires_at);

UPDATE work_items SET original_claimed_at = strftime('%Y-%m-%d %H:%M:%f', original_claimed_at)
 WHERE original_claimed_at IS NOT NULL AND typeof(original_claimed_at) = 'text'
   AND strftime('%Y-%m-%d %H:%M:%f', original_claimed_at) IS NOT NULL
   AND original_claimed_at <> strftime('%Y-%m-%d %H:%M:%f', original_claimed_at);

UPDATE notes SET created_at = strftime('%Y-%m-%d %H:%M:%f', created_at)
 WHERE created_at IS NOT NULL AND typeof(created_at) = 'text'
   AND strftime('%Y-%m-%d %H:%M:%f', created_at) IS NOT NULL
   AND created_at <> strftime('%Y-%m-%d %H:%M:%f', created_at);

UPDATE notes SET modified_at = strftime('%Y-%m-%d %H:%M:%f', modified_at)
 WHERE modified_at IS NOT NULL AND typeof(modified_at) = 'text'
   AND strftime('%Y-%m-%d %H:%M:%f', modified_at) IS NOT NULL
   AND modified_at <> strftime('%Y-%m-%d %H:%M:%f', modified_at);

UPDATE dependencies SET created_at = strftime('%Y-%m-%d %H:%M:%f', created_at)
 WHERE created_at IS NOT NULL AND typeof(created_at) = 'text'
   AND strftime('%Y-%m-%d %H:%M:%f', created_at) IS NOT NULL
   AND created_at <> strftime('%Y-%m-%d %H:%M:%f', created_at);

UPDATE plan_documents SET created_at = strftime('%Y-%m-%d %H:%M:%f', created_at)
 WHERE created_at IS NOT NULL AND typeof(created_at) = 'text'
   AND strftime('%Y-%m-%d %H:%M:%f', created_at) IS NOT NULL
   AND created_at <> strftime('%Y-%m-%d %H:%M:%f', created_at);

UPDATE plan_documents SET modified_at = strftime('%Y-%m-%d %H:%M:%f', modified_at)
 WHERE modified_at IS NOT NULL AND typeof(modified_at) = 'text'
   AND strftime('%Y-%m-%d %H:%M:%f', modified_at) IS NOT NULL
   AND modified_at <> strftime('%Y-%m-%d %H:%M:%f', modified_at);

UPDATE project_config SET updated_at = strftime('%Y-%m-%d %H:%M:%f', updated_at)
 WHERE updated_at IS NOT NULL AND typeof(updated_at) = 'text'
   AND strftime('%Y-%m-%d %H:%M:%f', updated_at) IS NOT NULL
   AND updated_at <> strftime('%Y-%m-%d %H:%M:%f', updated_at);

UPDATE role_transitions SET transitioned_at = strftime('%Y-%m-%d %H:%M:%f', transitioned_at)
 WHERE transitioned_at IS NOT NULL AND typeof(transitioned_at) = 'text'
   AND strftime('%Y-%m-%d %H:%M:%f', transitioned_at) IS NOT NULL
   AND transitioned_at <> strftime('%Y-%m-%d %H:%M:%f', transitioned_at);

UPDATE resource_leases SET acquired_at = strftime('%Y-%m-%d %H:%M:%f', acquired_at)
 WHERE acquired_at IS NOT NULL AND typeof(acquired_at) = 'text'
   AND strftime('%Y-%m-%d %H:%M:%f', acquired_at) IS NOT NULL
   AND acquired_at <> strftime('%Y-%m-%d %H:%M:%f', acquired_at);

UPDATE resource_leases SET expires_at = strftime('%Y-%m-%d %H:%M:%f', expires_at)
 WHERE expires_at IS NOT NULL AND typeof(expires_at) = 'text'
   AND strftime('%Y-%m-%d %H:%M:%f', expires_at) IS NOT NULL
   AND expires_at <> strftime('%Y-%m-%d %H:%M:%f', expires_at);

UPDATE resource_leases SET original_acquired_at = strftime('%Y-%m-%d %H:%M:%f', original_acquired_at)
 WHERE original_acquired_at IS NOT NULL AND typeof(original_acquired_at) = 'text'
   AND strftime('%Y-%m-%d %H:%M:%f', original_acquired_at) IS NOT NULL
   AND original_acquired_at <> strftime('%Y-%m-%d %H:%M:%f', original_acquired_at);

UPDATE resource_lease_history SET acquired_at = strftime('%Y-%m-%d %H:%M:%f', acquired_at)
 WHERE acquired_at IS NOT NULL AND typeof(acquired_at) = 'text'
   AND strftime('%Y-%m-%d %H:%M:%f', acquired_at) IS NOT NULL
   AND acquired_at <> strftime('%Y-%m-%d %H:%M:%f', acquired_at);

UPDATE resource_lease_history SET expires_at = strftime('%Y-%m-%d %H:%M:%f', expires_at)
 WHERE expires_at IS NOT NULL AND typeof(expires_at) = 'text'
   AND strftime('%Y-%m-%d %H:%M:%f', expires_at) IS NOT NULL
   AND expires_at <> strftime('%Y-%m-%d %H:%M:%f', expires_at);

UPDATE resource_lease_history SET released_at = strftime('%Y-%m-%d %H:%M:%f', released_at)
 WHERE released_at IS NOT NULL AND typeof(released_at) = 'text'
   AND strftime('%Y-%m-%d %H:%M:%f', released_at) IS NOT NULL
   AND released_at <> strftime('%Y-%m-%d %H:%M:%f', released_at);
