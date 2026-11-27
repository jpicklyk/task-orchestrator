-- V39: who a decision request waits on, and who answered it (decision 0003).
--
-- waiting_on: 'director' or 'user' while the request is pending and has been routed (delegate sets 'director',
-- escalate sets 'user'); NULL when it was never routed, and again once it is answered or withdrawn.
-- waiting_reason: why an escalated request waits on the user (1 to 500 characters); only beside waiting_on 'user'.
-- answer_decided_by: who made the decision an answer records, 'user' or 'director'; NULL for a seat answer and on every
-- pre-V39 answer. answer_basis: why that answerer could decide, 'autonomy', 'delegation' or 'relayed'.
-- answer_relayed: 1 when an agent relayed the user's decision. answer_within_autonomy: 1 when the director decided
-- within its autonomy (only with answer_decided_by 'director'). answer_links: JSON array of {kind, ref} evidence
-- pointers, NULL when none, parsed in Kotlin like links.
--
-- Additive only: seven nullable ADD COLUMNs with no default (schema-only, no row rewrite); no backfill, no index, no
-- existing object is touched. The cross-column rules are kept by DecisionService, the only writer. Forward-only like
-- every migration.

ALTER TABLE decision_requests ADD COLUMN waiting_on TEXT CHECK (waiting_on IS NULL OR waiting_on IN ('director', 'user'));
ALTER TABLE decision_requests ADD COLUMN waiting_reason TEXT CHECK (waiting_reason IS NULL OR length(waiting_reason) BETWEEN 1 AND 500);
ALTER TABLE decision_requests ADD COLUMN answer_decided_by TEXT CHECK (answer_decided_by IS NULL OR answer_decided_by IN ('user', 'director'));
ALTER TABLE decision_requests ADD COLUMN answer_basis TEXT CHECK (answer_basis IS NULL OR answer_basis IN ('autonomy', 'delegation', 'relayed'));
ALTER TABLE decision_requests ADD COLUMN answer_relayed INTEGER CHECK (answer_relayed IS NULL OR answer_relayed IN (0, 1));
ALTER TABLE decision_requests ADD COLUMN answer_within_autonomy INTEGER CHECK (answer_within_autonomy IS NULL OR answer_within_autonomy IN (0, 1));
ALTER TABLE decision_requests ADD COLUMN answer_links TEXT;
