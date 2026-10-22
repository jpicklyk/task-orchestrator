-- V29: Backfill role_transitions into events, then drop role_transitions (plan section 3.4, Q28b).
--
-- Since V20 every transition write also appended an item.transitioned event in the same unit of work, and since
-- Q28a that event carries the full audit (actor, verification, credentials, summary), so role_transitions has been a
-- redundant projection. Its remaining readers move to the events table; this migration relocates the rows that
-- predate the events table (they have no twin event) and removes the table. Backfill and DROP share one file because
-- a data step runs after every migration and so could never precede a Flyway DROP.
--
-- Boundary: a row is backfilled iff its transitioned_at is earlier than the installation of V20 (the events table);
-- later rows already have their twin event and are not copied. The boundary is the flyway_schema_history row of
-- version 20 (default history table name), normalized with strftime so a different text shape still compares. A
-- database without that row (hand-baselined) backfills every row.
--
-- Mapping: same id as the old row (a reverse script can rebuild the table 1:1); seq 1..N in transitioned_at order
-- (tie: id), all below the events seq floor (1e12), so the live AUTOINCREMENT counter is untouched and the SSE stream
-- never replays these rows; occurred_at is the canonical 23-char text; root_id is the item's stored root, else its own
-- id, else (orphan row with no work_items row) the item id itself; principal_id/kind and proof_status are the old
-- actor and verification columns (kind and status lower-cased as the event recorder writes them). data is a
-- json_object with keys in this fixed order: trigger, fromRole, toRole, fromStatusLabel, toStatusLabel, summary,
-- consumedCredentials, actorProofSha256, actorProofClaims, verifier, verificationReason, actorParent, backfilled.
-- backfilled is true and is the only marker (no origin). Invalid consumed_credentials JSON becomes [], invalid or '{}'
-- actor_proof_claims becomes null. The raw actor_proof column (NULL since V17) is not carried.
--
-- Unparseable timestamps: the table is dropped below, so a skipped row would be lost for good. A row whose
-- transitioned_at strftime cannot parse is backfilled anyway (whatever the V20 boundary) at a deterministic fallback
-- time: the item's created_at, else (orphan row, or an unparseable created_at) the epoch 1970-01-01 00:00:00.000. Its
-- data gains a trailing timestampRepaired: true. seq orders by the effective time, then parseable rows before repaired
-- ones, then id. The server logs the row counts around this migration (RoleTransitionBackfillCallback) and WARNs when
-- any row is not copied (dated at or after the V20 install, or already present in events by id).
--
-- Recovery: every audit column survives inside events.data, so the table can be rebuilt from the backfilled rows plus
-- the non-backfilled item.transitioned rows; the supported rollback is the volume backup taken before the first boot.

WITH src AS (
    SELECT rt.*,
           w.root_id AS w_root_id,
           w.id AS w_id,
           strftime('%Y-%m-%d %H:%M:%f', rt.transitioned_at) AS parsed_at,
           COALESCE(strftime('%Y-%m-%d %H:%M:%f', rt.transitioned_at),
                    strftime('%Y-%m-%d %H:%M:%f', w.created_at),
                    '1970-01-01 00:00:00.000') AS effective_at
      FROM role_transitions rt
      LEFT JOIN work_items w ON w.id = rt.item_id
),
picked AS (
    SELECT s.*,
           json_object(
               'trigger', s.trigger,
               'fromRole', s.from_role,
               'toRole', s.to_role,
               'fromStatusLabel', s.from_status_label,
               'toStatusLabel', s.to_status_label,
               'summary', s.summary,
               'consumedCredentials',
                   CASE WHEN s.consumed_credentials IS NOT NULL AND json_valid(s.consumed_credentials)
                             AND json_type(s.consumed_credentials) = 'array'
                        THEN json(s.consumed_credentials) ELSE json('[]') END,
               'actorProofSha256', s.actor_proof_sha256,
               'actorProofClaims',
                   CASE WHEN s.actor_proof_claims IS NOT NULL AND json_valid(s.actor_proof_claims)
                             AND json_type(s.actor_proof_claims) = 'object' AND s.actor_proof_claims <> '{}'
                        THEN json(s.actor_proof_claims) ELSE NULL END,
               'verifier', s.verification_verifier,
               'verificationReason', s.verification_reason,
               'actorParent', s.actor_parent,
               'backfilled', json('true')
           ) AS payload
      FROM src s
     WHERE (s.parsed_at IS NULL
            OR s.parsed_at <
               COALESCE((SELECT strftime('%Y-%m-%d %H:%M:%f', MIN(installed_on)) FROM flyway_schema_history WHERE version = '20'),
                        '9999-12-31 00:00:00.000'))
       AND NOT EXISTS (SELECT 1 FROM events e WHERE e.id = s.id)
)
INSERT INTO events (seq, id, occurred_at, root_id, entity_kind, entity_id, type, data, principal_id, principal_kind, proof_status)
SELECT ROW_NUMBER() OVER (ORDER BY p.effective_at, p.parsed_at IS NULL, p.id),
       p.id,
       p.effective_at,
       COALESCE(p.w_root_id, p.w_id, p.item_id),
       'item',
       p.item_id,
       'item.transitioned',
       CASE WHEN p.parsed_at IS NULL THEN json_set(p.payload, '$.timestampRepaired', json('true')) ELSE p.payload END,
       p.actor_id,
       lower(p.actor_kind),
       lower(p.verification_status)
  FROM picked p;

DROP TABLE role_transitions;
