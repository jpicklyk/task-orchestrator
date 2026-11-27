-- V40: the sibling rank (decision 0013: an order the user or a plan stated among items of equal priority).
--
-- work_items.rank: an optional integer place in the order `next` hands out among items of equal priority; a smaller
-- rank comes first and any integer is valid (0 and negatives included). NULL means unranked and sorts after every
-- ranked item, so every pre-V40 row reads as unranked. Rank is a sort key only: it adds no edge and no gate, and never
-- changes which items are eligible.
--
-- Additive only: one nullable ADD COLUMN with no default (schema-only, no row rewrite); no backfill, no index, no
-- existing object is touched. Forward-only like every migration.

ALTER TABLE work_items ADD COLUMN rank INTEGER;
