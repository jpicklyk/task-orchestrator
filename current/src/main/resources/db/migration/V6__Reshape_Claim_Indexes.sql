-- V6: Reshape claim-expiry index
--
-- The V5 partial index `idx_work_items_claim_expires WHERE claimed_by IS NOT NULL` is unused
-- by `findForNextItem`-class queries that OR with `claimed_by IS NULL` (the WHERE does not
-- imply the partial predicate, so the SQLite planner falls back to a full table scan).
-- Replace with a non-partial index so claim-expiry comparisons can use it regardless of
-- claim presence.
--
-- Index inventory after V6:
--   idx_work_items_claimed_by    ON work_items(claimed_by)
--     Used by: countByClaimStatus (claimedBy IS NOT NULL / IS NULL filters),
--              auto-release step (WHERE claimed_by = ? AND HEX(id) != ?)
--
--   idx_work_items_claim_expires ON work_items(claim_expires_at)  [non-partial]
--     Used by: findForNextItem expiry filter
--              (claim_expires_at <= datetime('now') OR claimed_by IS NULL),
--              countByClaimStatus expired-bucket scan
--              (claim_expires_at <= now AND claimed_by IS NOT NULL)

DROP INDEX IF EXISTS idx_work_items_claim_expires;

CREATE INDEX idx_work_items_claim_expires
  ON work_items(claim_expires_at);
