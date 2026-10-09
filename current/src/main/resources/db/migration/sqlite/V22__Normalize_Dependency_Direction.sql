-- V22: Normalize dependency direction (AR-03, plan 3.4).
--
-- IS_BLOCKED_BY becomes an input alias only: every blocking dependency is stored as BLOCKS from the
-- blocker to the blocked item. This migration rewrites the stored IS_BLOCKED_BY rows and tightens the
-- type CHECK to ('BLOCKS', 'RELATES_TO'). SQLite cannot alter a CHECK, so the table is recreated.
--
-- Data rules:
--   * Twins. An IS_BLOCKED_BY row a->b states the same edge as a BLOCKS row b->a. Both thresholds had to
--     hold, so the stricter unblock_at survives on the BLOCKS row (rank queue < work < review < terminal,
--     NULL ranks as terminal; a tie keeps the BLOCKS value). The BLOCKS row keeps its id and created_at;
--     the IS_BLOCKED_BY twin is dropped.
--   * Every other IS_BLOCKED_BY row is rewritten in place under the same id: ends swapped, type BLOCKS,
--     unblock_at and created_at kept. It cannot collide with the unique index, because a colliding BLOCKS
--     row would have been its twin.
--   * Mutual blocks (a BLOCKS b plus b BLOCKS a) are kept; StartupIntegrity.reportMutualBlocks reports them.
--   * RELATES_TO rows are copied unchanged.
--
-- This is a one-way data rewrite: the only rollback is a pre-upgrade file backup. Stop every 3.x process
-- before upgrading: a 3.x writer inserting an IS_BLOCKED_BY row fails the new CHECK.
--
-- Table recreation is safe as-is: Flyway runs with foreign_keys=0, nothing references dependencies, and the
-- table has no triggers or FTS shadow. Rowids are preserved.

-- 1. Keep the stricter threshold on the BLOCKS row of each twin pair.
UPDATE dependencies
SET unblock_at = (
    SELECT t.unblock_at FROM dependencies t
    WHERE t.type = 'IS_BLOCKED_BY'
      AND t.from_item_id = dependencies.to_item_id
      AND t.to_item_id = dependencies.from_item_id
)
WHERE type = 'BLOCKS'
  AND EXISTS (
    SELECT 1 FROM dependencies t
    WHERE t.type = 'IS_BLOCKED_BY'
      AND t.from_item_id = dependencies.to_item_id
      AND t.to_item_id = dependencies.from_item_id
      AND (CASE t.unblock_at WHEN 'queue' THEN 0 WHEN 'work' THEN 1 WHEN 'review' THEN 2 ELSE 3 END)
        > (CASE dependencies.unblock_at WHEN 'queue' THEN 0 WHEN 'work' THEN 1 WHEN 'review' THEN 2 ELSE 3 END)
  );

-- 2. Recreate the table with the tightened CHECK.
CREATE TABLE dependencies_new (
    id              BLOB PRIMARY KEY DEFAULT (randomblob(16)),
    from_item_id    BLOB NOT NULL REFERENCES work_items(id) ON DELETE CASCADE,
    to_item_id      BLOB NOT NULL REFERENCES work_items(id) ON DELETE CASCADE,
    type            VARCHAR(20) NOT NULL DEFAULT 'BLOCKS'
                    CHECK (type IN ('BLOCKS', 'RELATES_TO')),
    unblock_at      VARCHAR(20) CHECK (unblock_at IS NULL OR unblock_at IN ('queue', 'work', 'review', 'terminal')),
    created_at      TIMESTAMP NOT NULL
);

-- 3. Copy: drop the twinned IS_BLOCKED_BY rows, swap the ends of the others and make them BLOCKS.
INSERT INTO dependencies_new (rowid, id, from_item_id, to_item_id, type, unblock_at, created_at)
SELECT d.rowid,
       d.id,
       CASE d.type WHEN 'IS_BLOCKED_BY' THEN d.to_item_id ELSE d.from_item_id END,
       CASE d.type WHEN 'IS_BLOCKED_BY' THEN d.from_item_id ELSE d.to_item_id END,
       CASE d.type WHEN 'IS_BLOCKED_BY' THEN 'BLOCKS' ELSE d.type END,
       d.unblock_at,
       d.created_at
FROM dependencies d
WHERE d.type <> 'IS_BLOCKED_BY'
   OR NOT EXISTS (
        SELECT 1 FROM dependencies b
        WHERE b.type = 'BLOCKS'
          AND b.from_item_id = d.to_item_id
          AND b.to_item_id = d.from_item_id
   );

DROP TABLE dependencies;
ALTER TABLE dependencies_new RENAME TO dependencies;

CREATE UNIQUE INDEX idx_deps_unique ON dependencies(from_item_id, to_item_id, type);
CREATE INDEX idx_deps_from ON dependencies(from_item_id);
CREATE INDEX idx_deps_to ON dependencies(to_item_id);
