-- V9: Denormalized root_id column
--
-- Adds a nullable root_id column to work_items: the UUID of the item's depth-0 ancestor
-- (or the item's own id when it IS the depth-0 root). This denormalizes what would
-- otherwise require an unbounded ancestor walk (recursive CTE) on every scope-filtered
-- query, at the cost of maintaining it on write (CreateItemHandler on create;
-- ItemHierarchyValidator.recomputeDescendantDepths + its two call sites on reparent).
--
-- Additive only: SQLite's ALTER TABLE ADD COLUMN does not require table recreation
-- (unlike V7's structural change), so this migration is a plain ALTER + backfill.
--
-- No FOREIGN KEY / REFERENCES clause: a depth-0 root's root_id equals its own id, i.e. a
-- self-reference at INSERT time. SQLite FK enforcement is off by default in this project
-- (see V7 notes — PRAGMA foreign_keys can't change mid-transaction), so this is harmless
-- here, but omitting the constraint keeps the column consistent with how the Exposed
-- WorkItemsTable.kt definition represents it (no explicit foreignKey() binding), avoiding
-- any self-reference edge case on stricter FK-enforcing engines used elsewhere in the stack.
--
-- Nullable, no NOT NULL constraint: SQLite cannot add a NOT NULL column without a constant
-- DEFAULT in a single ALTER, and a constant default is wrong here since every row needs a
-- distinct backfilled value. Existing rows are backfilled below in this migration's
-- transaction. Rows unreachable from any depth-0 root (a parent_id pointing at a
-- since-deleted or otherwise missing id) are intentionally left with root_id = NULL — the
-- documented "not backfilled / orphaned" state. New rows are always stamped with a non-null
-- root_id by the application layer going forward.

ALTER TABLE work_items ADD COLUMN root_id BLOB;

CREATE INDEX idx_work_items_root_id ON work_items(root_id);

-- Backfill: recursive walk from every depth-0 root (parent_id IS NULL) down through all
-- descendants, seeding root_id = own id at each root and propagating it unchanged to every
-- descendant. Correctly handles multiple independent root trees (each gets its own
-- root_id). UPDATE ... FROM joins the CTE once rather than re-evaluating it via correlated
-- subqueries. Requires SQLite >= 3.33 (UPDATE...FROM) — already satisfied by the >= 3.45
-- baseline V7 established for the trigram tokenizer (bundled org.xerial:sqlite-jdbc:3.49.1.0).
WITH RECURSIVE root_walk(id, computed_root_id) AS (
    SELECT id, id
    FROM   work_items
    WHERE  parent_id IS NULL
    UNION ALL
    SELECT wi.id, rw.computed_root_id
    FROM   work_items wi
    JOIN   root_walk rw ON wi.parent_id = rw.id
)
UPDATE work_items
SET    root_id = root_walk.computed_root_id
FROM   root_walk
WHERE  work_items.id = root_walk.id;
