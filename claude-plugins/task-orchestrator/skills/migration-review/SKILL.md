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
- **New tables** — check foreign key ordering in `DirectDatabaseSchemaManager`
- **Index changes** — `CREATE INDEX` / `DROP INDEX` work normally

## Step 2: SQLite Constraint Check

Verify against known SQLite limitations:
- [ ] No `ALTER COLUMN` — if modifying existing columns, table recreation pattern is used
- [ ] No `DROP COLUMN` in older SQLite versions — check if the Docker image's SQLite supports it
- [ ] Foreign key constraints — new tables must be inserted in correct order in `DirectDatabaseSchemaManager`
- [ ] `TEXT` affinity — SQLite stores all strings as TEXT regardless of declared type
- [ ] No concurrent write transactions — migrations must be sequential

## Step 3: Data Migration Strategy

For migrations that modify existing data:
- [ ] Existing rows handled — default values for new columns, or explicit data migration
- [ ] Null safety — new NOT NULL columns require a DEFAULT or data backfill
- [ ] Large table performance — SQLite locks the entire database during writes

## Step 4: Flyway Integration

- [ ] Migration file follows naming: `V{N}__{Description}.sql`
- [ ] Version number is sequential (no gaps, no conflicts with existing migrations)
- [ ] Migration is idempotent where possible
- [ ] `DirectDatabaseSchemaManager` updated if new tables are added (insert in FK dependency order)

## Step 5: Rollback Considerations

- [ ] Can the migration be reversed manually if needed?
- [ ] Is the schema change backward compatible with the previous application version?
- [ ] Docker volume data survives container restarts — migration is permanent

## Output

Compose the `migration-assessment` note with findings from each step. Flag any SQLite-specific risks. Reference `project-concerns.md` for additional codebase constraints.
