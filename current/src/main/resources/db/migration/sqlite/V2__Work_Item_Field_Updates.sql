-- V2: Make complexity nullable, add requires_verification column
-- SQLite does not support ALTER COLUMN, so table recreation is required.

-- Step 1: Create new table with updated schema
CREATE TABLE work_items_new (
    id              BLOB PRIMARY KEY DEFAULT (randomblob(16)),
    parent_id       BLOB REFERENCES work_items_new(id),
    title           TEXT NOT NULL,
    description     TEXT,
    summary         TEXT NOT NULL DEFAULT '',
    role            TEXT NOT NULL DEFAULT 'queue'
                    CHECK (role IN ('queue', 'work', 'review', 'blocked', 'terminal')),
    status_label    TEXT,
    previous_role   TEXT CHECK (previous_role IS NULL OR previous_role IN ('queue', 'work', 'review', 'blocked', 'terminal')),
    priority        TEXT NOT NULL DEFAULT 'medium'
                    CHECK (priority IN ('high', 'medium', 'low')),
    complexity      INTEGER,
    requires_verification INTEGER NOT NULL DEFAULT 0,
    depth           INTEGER NOT NULL DEFAULT 0,
    metadata        TEXT,
    tags            TEXT,
    created_at      TIMESTAMP NOT NULL,
    modified_at     TIMESTAMP NOT NULL,
    role_changed_at TIMESTAMP NOT NULL,
    version         INTEGER NOT NULL DEFAULT 1
);

-- Step 2: Copy existing data (complexity migrated as-is; requires_verification defaults to 0/false)
INSERT INTO work_items_new (
    id, parent_id, title, description, summary,
    role, status_label, previous_role, priority,
    complexity, requires_verification, depth,
    metadata, tags, created_at, modified_at, role_changed_at, version
)
SELECT
    id, parent_id, title, description, summary,
    role, status_label, previous_role, priority,
    complexity, 0, depth,
    metadata, tags, created_at, modified_at, role_changed_at, version
FROM work_items;

-- Step 3: Drop old table
DROP TABLE work_items;

-- Step 4: Rename new table
ALTER TABLE work_items_new RENAME TO work_items;

-- Step 5: Recreate indexes
CREATE INDEX IF NOT EXISTS idx_work_items_parent ON work_items(parent_id);
CREATE INDEX IF NOT EXISTS idx_work_items_role ON work_items(role);
CREATE INDEX IF NOT EXISTS idx_work_items_depth ON work_items(depth);
CREATE INDEX IF NOT EXISTS idx_work_items_priority ON work_items(priority);
CREATE INDEX IF NOT EXISTS idx_work_items_role_changed ON work_items(role, role_changed_at);
