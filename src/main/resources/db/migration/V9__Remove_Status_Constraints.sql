-- V9: Remove status CHECK constraints to enable v2.0 config-driven status system
--
-- Background: Task Orchestrator v2.0 introduces configurable status workflows.
-- When .taskorchestrator/config.yaml exists, validation uses config instead of enums.
-- This migration removes database-level CHECK constraints to enable any status string.
--
-- Changes:
-- 1. Remove CHECK constraints from status columns (projects, features, tasks)
-- 2. Increase status VARCHAR from 20 to 50 to accommodate longer custom status names
-- 3. Preserve all existing data (no transformations)
--
-- Note: SQLite doesn't support ALTER TABLE to drop CHECK constraints directly.
-- Solution: Create new tables without constraints, copy data, drop old, rename new.
--
-- Backward Compatibility: v1.0 (enum) mode still works when no config.yaml exists.

-- ============================================================================
-- Projects Table Rebuild
-- ============================================================================

-- Create new projects table without status CHECK constraint
CREATE TABLE projects_new (
    id BLOB PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    summary TEXT NOT NULL,
    status VARCHAR(50) NOT NULL,  -- Increased from 20 to 50, NO CHECK constraint
    created_at TIMESTAMP NOT NULL,
    modified_at TIMESTAMP NOT NULL,
    search_vector TEXT,
    version BIGINT NOT NULL DEFAULT 1  -- Added in V3
);

-- Copy all data from old table (V1 + V3 columns)
INSERT INTO projects_new (id, name, summary, status, created_at, modified_at, search_vector, version)
SELECT id, name, summary, status, created_at, modified_at, search_vector, version FROM projects;

-- Drop old table
DROP TABLE projects;

-- Rename new table to original name
ALTER TABLE projects_new RENAME TO projects;

-- Recreate indexes
CREATE INDEX idx_projects_status ON projects(status);
CREATE INDEX idx_projects_created_at ON projects(created_at);
CREATE INDEX idx_projects_modified_at ON projects(modified_at);
CREATE INDEX idx_projects_version ON projects(version);  -- Added in V3

-- ============================================================================
-- Features Table Rebuild
-- ============================================================================

-- Create new features table without status CHECK constraint
CREATE TABLE features_new (
    id BLOB PRIMARY KEY,
    project_id BLOB,
    name TEXT NOT NULL,
    summary TEXT NOT NULL,
    status VARCHAR(50) NOT NULL,  -- Increased from 20 to 50, NO CHECK constraint
    priority VARCHAR(10) NOT NULL CHECK (priority IN ('HIGH', 'MEDIUM', 'LOW')),  -- Keep priority constraint
    created_at TIMESTAMP NOT NULL,
    modified_at TIMESTAMP NOT NULL,
    search_vector TEXT,
    version INTEGER NOT NULL DEFAULT 1,  -- Added in V3
    description TEXT,  -- Added in V6
    FOREIGN KEY (project_id) REFERENCES projects(id)
);

-- Copy all data from old table (V1 + V3 + V6 + V7 columns)
INSERT INTO features_new (id, project_id, name, summary, status, priority, created_at, modified_at, search_vector, version, description)
SELECT id, project_id, name, summary, status, priority, created_at, modified_at, search_vector, version, description FROM features;

-- Drop old table
DROP TABLE features;

-- Rename new table to original name
ALTER TABLE features_new RENAME TO features;

-- Recreate indexes
CREATE INDEX idx_features_project_id ON features(project_id);
CREATE INDEX idx_features_status ON features(status);
CREATE INDEX idx_features_priority ON features(priority);
CREATE INDEX idx_features_created_at ON features(created_at);
CREATE INDEX idx_features_modified_at ON features(modified_at);
CREATE INDEX idx_features_version ON features(version);  -- Added in V3
-- V8 indexes
CREATE INDEX idx_features_status_priority ON features(status, priority);
CREATE INDEX idx_features_project_status ON features(project_id, status);

-- ============================================================================
-- Tasks Table Rebuild
-- ============================================================================

-- Create new tasks table without status CHECK constraint
CREATE TABLE tasks_new (
    id BLOB PRIMARY KEY,
    project_id BLOB,
    feature_id BLOB,
    title TEXT NOT NULL,
    summary TEXT NOT NULL,
    status VARCHAR(50) NOT NULL,  -- Increased from 20 to 50, NO CHECK constraint
    priority VARCHAR(20) NOT NULL CHECK (priority IN ('HIGH', 'MEDIUM', 'LOW')),  -- Keep priority constraint
    complexity INTEGER NOT NULL,
    created_at TIMESTAMP NOT NULL,
    modified_at TIMESTAMP NOT NULL,
    version INTEGER NOT NULL DEFAULT 1,
    last_modified_by TEXT,
    lock_status VARCHAR(20) NOT NULL DEFAULT 'UNLOCKED' CHECK (lock_status IN ('UNLOCKED', 'LOCKED_EXCLUSIVE', 'LOCKED_SHARED', 'LOCKED_SECTION')),  -- Keep lock_status constraint
    search_vector TEXT,
    description TEXT,  -- Added in V6
    FOREIGN KEY (project_id) REFERENCES projects(id),
    FOREIGN KEY (feature_id) REFERENCES features(id)
);

-- Copy all data from old table (V1 + V6 + V7 columns)
INSERT INTO tasks_new (id, project_id, feature_id, title, summary, status, priority, complexity, created_at, modified_at, version, last_modified_by, lock_status, search_vector, description)
SELECT id, project_id, feature_id, title, summary, status, priority, complexity, created_at, modified_at, version, last_modified_by, lock_status, search_vector, description FROM tasks;

-- Drop old table
DROP TABLE tasks;

-- Rename new table to original name
ALTER TABLE tasks_new RENAME TO tasks;

-- Recreate indexes
CREATE INDEX idx_tasks_project_id ON tasks(project_id);
CREATE INDEX idx_tasks_feature_id ON tasks(feature_id);
CREATE INDEX idx_tasks_status ON tasks(status);
CREATE INDEX idx_tasks_priority ON tasks(priority);
CREATE INDEX idx_tasks_version ON tasks(version);
CREATE INDEX idx_tasks_lock_status ON tasks(lock_status);
CREATE INDEX idx_tasks_last_modified_by ON tasks(last_modified_by);
-- V4 performance indexes
CREATE INDEX idx_tasks_feature_status ON tasks(feature_id, status);
CREATE INDEX idx_tasks_project_feature ON tasks(project_id, feature_id);
-- V8 indexes
CREATE INDEX idx_tasks_status_priority_complexity ON tasks(status, priority, complexity);
CREATE INDEX idx_tasks_feature_status_priority ON tasks(feature_id, status, priority);

-- ============================================================================
-- Summary
-- ============================================================================
-- Migration complete. Status columns now:
-- - Accept any string value (no CHECK constraints)
-- - Support longer names (VARCHAR(50) vs VARCHAR(20))
-- - All existing data preserved
-- - All indexes recreated
--
-- v2.0 Behavior:
-- - Without config.yaml: Enum-based validation (v1.0 legacy mode)
-- - With config.yaml: Config-based validation (v2.0 mode)
--
-- Trigger: Running setup_claude_agents creates config.yaml and enables v2.0 mode.
