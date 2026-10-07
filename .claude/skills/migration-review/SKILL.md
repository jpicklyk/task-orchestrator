---
name: migration-review
description: SQLite migration assessment for items with the needs-migration-review trait. Evaluates schema changes, table recreation patterns, data migration strategy, and Flyway migration correctness. Invoked via skillPointer when filling migration-assessment notes.
user-invocable: false
---

# Migration Review Framework

Evaluate database migration safety for SQLite-specific constraints. This project uses Flyway migrations with SQLite, which has significant limitations compared to PostgreSQL/MySQL.

## Step 1: Identify Schema Changes

Read the changed files and migration SQL to determine:
- **New columns** — `ALTER TABLE ADD COLUMN` works in SQLite
- **Modified columns** — SQLite has NO `ALTER COLUMN`. Requires table recreation:
  1. Create new table with desired schema
  2. Copy data from old table
  3. Drop old table
  4. Rename new table
- **New tables** — a Flyway migration is the only place a table is defined (the Exposed table is a query mapping); there is no second table list to update
- **Index changes** — `CREATE INDEX` / `DROP INDEX` work normally

## Step 2: SQLite Constraint Check

Verify against known SQLite limitations:
- [ ] No `ALTER COLUMN` — if modifying existing columns, table recreation pattern is used
- [ ] No `DROP COLUMN` in older SQLite versions — check if the Docker image's SQLite supports it
- [ ] Foreign key constraints — Flyway's connection runs with `foreign_keys = OFF` (see the template below), so a `DROP TABLE` does not cascade; the application connection runs with it ON
- [ ] `TEXT` affinity — SQLite stores all strings as TEXT regardless of declared type
- [ ] No concurrent write transactions — migrations must be sequential

## Step 3: Data Migration Strategy

For migrations that modify existing data:
- [ ] Existing rows handled — default values for new columns, or explicit data migration
- [ ] Null safety — new NOT NULL columns require a DEFAULT or data backfill
- [ ] Large table performance — SQLite locks the entire database during writes
- [ ] Runtime-dependent guarantees are probed, not assumed — see below

**Guarantees that rest on engine runtime behavior need a multi-row probe.** When the assessment claims an at-rest, security, or data-integrity guarantee that depends on a specific runtime/engine behavior (e.g. SQLite `PRAGMA secure_delete`, `VACUUM` page reuse, WAL checkpoint timing, FTS5 shadow-table residue), the assessment MUST specify a multi-row/multi-write empirical probe as part of the required test strategy — not a single-row assertion, which can read clean whether or not the mechanism works. It MUST NOT phrase the guarantee as absolute ("scrubbed", "removed") when only a probabilistic or partial mechanism is available ("reduces residue", "partial scrub"); once the probe exists, state the measured bound (e.g. "3–14 of 200 rows survive with the pragma, 200+ without").

## Step 4: Flyway Integration

- [ ] Migration file follows naming: `V{N}__{Description}.sql`
- [ ] Version number is sequential (no gaps, no conflicts with existing migrations)
- [ ] Migration is idempotent where possible
- [ ] Migration files stay byte-identical once released: Flyway's checksum covers every line including comments, so never edit one (V9's header comment, lines 12-14, says FK enforcement is off by default in this project; in fact the application connection runs `foreign_keys=ON` (DatabaseManager) and only Flyway's own connection runs with it OFF. That correction lives here, not in the file, because Flyway checksums comment lines)
- [ ] Expand/contract: ship additive changes first and remove the old shape in a later release; flag any table-recreating migration in the release notes
- [ ] Upgrade harness seed added: `upgrade/seeds/SeedV{N}.kt` extends `MigrationSeed({N})`, lists any rewritten `table.column` pairs in `rewrites`, seeds rows the migration transforms, and asserts the result in `verify`. The harness migrates a populated database from N-1 and checks rows, foreign keys, FTS counts and integrity, the trigger inventory and `foreign_keys = 0` on Flyway's connection; it fails for a V18-or-later migration without a seed

### Table-recreation template

Migrations live in `current/src/main/resources/db/migration/sqlite/`. A migration that recreates a table
(SQLite has no `ALTER COLUMN`) must:
1. Run on Flyway's connection, which has `foreign_keys = OFF` and `busy_timeout` set (do not rely on cascades; re-check child rows after).
2. Create the new table, then `INSERT INTO new_t(rowid, c1, ...) SELECT rowid, c1, ... FROM t` to PRESERVE rowids (without the explicit column list the rowid lands in the first declared column): external-content FTS5 tables join on `rowid`.
3. Drop the old table, rename the new one, recreate its indexes.
4. Recreate every trigger the dropped table owned, since dropping a table drops its triggers: for `work_items`, 6 FTS sync triggers (`work_items_fts_trigram_ai/_ad/_au`, `work_items_fts_text_ai/_ad/_au`) plus 2 cycle triggers (`work_items_cycle_check`, `work_items_cycle_check_update`); for `notes`, 6 FTS sync triggers (`notes_fts_trigram_ai/_ad/_au`, `notes_fts_text_ai/_ad/_au`). The `_au` triggers use their V8 form (restricted to the indexed columns).
5. `INSERT INTO <fts>(<fts>) VALUES('rebuild')` for all 4 FTS tables.
Startup then verifies the trigger/FTS inventory and runs the FTS `integrity-check`, so a missed trigger fails startup. Never use `VACUUM` in a migration: it can renumber rowids.

## Step 5: Rollback Considerations

- [ ] Can the migration be reversed manually if needed?
- [ ] Is the schema change backward compatible with the previous application version?
- [ ] Docker volume data survives container restarts — migration is permanent

## Output

Compose the `migration-assessment` note with findings from each step. Flag any SQLite-specific risks. Reference `.claude/skills/spec-quality/references/project-concerns.md` for additional codebase constraints.
