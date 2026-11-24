-- V38: the pinned builds flag (decision 0004: next returns startable work).
--
-- work_items.builds: whether the item's pinned schema and traits make it work that builds (1) or not (0). It is written
-- with the schema pin, at every pin point, and is NULL when the item is schema-free and on every pre-V38 row. NULL is
-- READ as "builds": the next and claim-select eligibility predicate is `builds IS NULL OR builds = 1`, so every pre-V38
-- row stays eligible exactly as before until it next re-pins.
--
-- Additive only: one nullable ADD COLUMN with no default (schema-only, no row rewrite); no backfill, no index, no
-- existing object is touched. Forward-only like every migration.

ALTER TABLE work_items ADD COLUMN builds INTEGER CHECK (builds IS NULL OR builds IN (0, 1));
