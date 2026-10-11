-- V27: typed item traits (plan section 3.7, Q15). One row per (item, position); the position keeps the
-- caller's order, the (item, name) uniqueness keeps a name from appearing twice on one item. Rows are
-- written by the item store on create/update and by the traits-from-properties data step on the first
-- 4.0 boot; the migration itself backfills nothing (SQL cannot parse every 3.x properties form safely).
CREATE TABLE item_traits (
    item_id  BLOB    NOT NULL REFERENCES work_items(id) ON DELETE CASCADE,
    position INTEGER NOT NULL CHECK (position >= 0),
    name     TEXT    NOT NULL CHECK (length(name) BETWEEN 1 AND 100),
    PRIMARY KEY (item_id, position),
    UNIQUE (item_id, name)
);
CREATE INDEX idx_item_traits_name ON item_traits(name);
