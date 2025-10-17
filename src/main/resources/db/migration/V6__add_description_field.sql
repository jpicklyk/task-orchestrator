-- Migration to add description field to tasks, features, and projects
-- This separates two purposes:
--   - description: User-provided "what to do" (preserved from existing summary)
--   - summary: Agent-generated "what was done" (will be populated by Task Manager END mode)

-- =============================================================================
-- Add description column to projects table
-- =============================================================================

ALTER TABLE projects ADD COLUMN description TEXT;

-- Migrate existing summary data to description (preserving user content)
UPDATE projects SET description = summary;

-- Make description NOT NULL now that data is migrated
-- SQLite doesn't support ALTER COLUMN, so we use a constraint check
-- Note: New rows will need to provide description
CREATE TABLE projects_new (
    id BLOB PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    summary TEXT NOT NULL,
    description TEXT NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('PLANNING', 'IN_DEVELOPMENT', 'COMPLETED', 'ARCHIVED')),
    created_at TIMESTAMP NOT NULL,
    modified_at TIMESTAMP NOT NULL,
    version INTEGER NOT NULL DEFAULT 1,
    search_vector TEXT
);

INSERT INTO projects_new SELECT id, name, summary, description, status, created_at, modified_at, version, search_vector FROM projects;
DROP TABLE projects;
ALTER TABLE projects_new RENAME TO projects;

-- Recreate indexes for projects
CREATE INDEX idx_projects_status ON projects(status);
CREATE INDEX idx_projects_created_at ON projects(created_at);
CREATE INDEX idx_projects_modified_at ON projects(modified_at);
CREATE INDEX idx_projects_version ON projects(version);
CREATE INDEX idx_projects_search_vector ON projects(search_vector);

-- Clear summary field (will be populated by Task Manager)
UPDATE projects SET summary = '';

-- =============================================================================
-- Add description column to features table
-- =============================================================================

ALTER TABLE features ADD COLUMN description TEXT;

-- Migrate existing summary data to description (preserving user content)
UPDATE features SET description = summary;

-- Make description NOT NULL now that data is migrated
CREATE TABLE features_new (
    id BLOB PRIMARY KEY,
    project_id BLOB,
    name TEXT NOT NULL,
    summary TEXT NOT NULL,
    description TEXT NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('PLANNING', 'IN_DEVELOPMENT', 'COMPLETED', 'ARCHIVED')),
    priority VARCHAR(10) NOT NULL CHECK (priority IN ('HIGH', 'MEDIUM', 'LOW')),
    created_at TIMESTAMP NOT NULL,
    modified_at TIMESTAMP NOT NULL,
    version INTEGER NOT NULL DEFAULT 1,
    search_vector TEXT,
    FOREIGN KEY (project_id) REFERENCES projects(id)
);

INSERT INTO features_new SELECT id, project_id, name, summary, description, status, priority, created_at, modified_at, version, search_vector FROM features;
DROP TABLE features;
ALTER TABLE features_new RENAME TO features;

-- Recreate indexes for features
CREATE INDEX idx_features_project_id ON features(project_id);
CREATE INDEX idx_features_status ON features(status);
CREATE INDEX idx_features_priority ON features(priority);
CREATE INDEX idx_features_created_at ON features(created_at);
CREATE INDEX idx_features_modified_at ON features(modified_at);
CREATE INDEX idx_features_version ON features(version);
CREATE INDEX idx_features_search_vector ON features(search_vector);

-- Clear summary field (will be populated by Task Manager)
UPDATE features SET summary = '';

-- =============================================================================
-- Add description column to tasks table
-- =============================================================================

ALTER TABLE tasks ADD COLUMN description TEXT;

-- Migrate existing summary data to description (preserving user content)
UPDATE tasks SET description = summary;

-- Make description NOT NULL now that data is migrated
CREATE TABLE tasks_new (
    id BLOB PRIMARY KEY,
    project_id BLOB,
    feature_id BLOB,
    title TEXT NOT NULL,
    summary TEXT NOT NULL,
    description TEXT NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('PENDING', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED', 'DEFERRED')),
    priority VARCHAR(20) NOT NULL CHECK (priority IN ('HIGH', 'MEDIUM', 'LOW')),
    complexity INTEGER NOT NULL,
    created_at TIMESTAMP NOT NULL,
    modified_at TIMESTAMP NOT NULL,
    version INTEGER NOT NULL DEFAULT 1,
    last_modified_by TEXT,
    lock_status VARCHAR(20) NOT NULL DEFAULT 'UNLOCKED' CHECK (lock_status IN ('UNLOCKED', 'LOCKED_EXCLUSIVE', 'LOCKED_SHARED', 'LOCKED_SECTION')),
    search_vector TEXT,
    FOREIGN KEY (project_id) REFERENCES projects(id),
    FOREIGN KEY (feature_id) REFERENCES features(id)
);

INSERT INTO tasks_new SELECT id, project_id, feature_id, title, summary, description, status, priority, complexity, created_at, modified_at, version, last_modified_by, lock_status, search_vector FROM tasks;
DROP TABLE tasks;
ALTER TABLE tasks_new RENAME TO tasks;

-- Recreate indexes for tasks
CREATE INDEX idx_tasks_project_id ON tasks(project_id);
CREATE INDEX idx_tasks_feature_id ON tasks(feature_id);
CREATE INDEX idx_tasks_status ON tasks(status);
CREATE INDEX idx_tasks_priority ON tasks(priority);
CREATE INDEX idx_tasks_version ON tasks(version);
CREATE INDEX idx_tasks_lock_status ON tasks(lock_status);
CREATE INDEX idx_tasks_last_modified_by ON tasks(last_modified_by);
CREATE INDEX idx_tasks_search_vector ON tasks(search_vector);
CREATE INDEX idx_tasks_status_priority ON tasks(status, priority);
CREATE INDEX idx_tasks_feature_status ON tasks(feature_id, status);
CREATE INDEX idx_tasks_project_status ON tasks(project_id, status);

-- Clear summary field (will be populated by Task Manager)
UPDATE tasks SET summary = '';

-- =============================================================================
-- Migration complete
-- =============================================================================
-- After this migration:
-- - description contains the original user-provided "what to do" content
-- - summary is empty and will be populated by Task Manager END mode with "what was done"
-- - Both fields are NOT NULL to ensure data integrity

-- =============================================================================
-- ROLLBACK INSTRUCTIONS
-- =============================================================================
-- To rollback this migration, you would need to:
-- 1. Copy summary content back to description if needed
-- 2. DROP the description column from all three tables
-- 3. Restore summary field with original values (if backup exists)
--
-- Example rollback SQL (requires backup of original data):
--
-- -- For projects
-- ALTER TABLE projects DROP COLUMN description;
--
-- -- For features
-- ALTER TABLE features DROP COLUMN description;
--
-- -- For tasks
-- ALTER TABLE tasks DROP COLUMN description;
--
-- Note: This is a destructive migration. Ensure you have backups before applying.
-- The summary field is cleared after migration, so rollback requires restoring from backup.
