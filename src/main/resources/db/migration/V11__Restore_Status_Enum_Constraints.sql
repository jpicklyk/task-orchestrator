-- V11: Restore status CHECK constraints with v2.0 enum expansions
--
-- Background: V9 removed status CHECK constraints to enable config-driven workflows.
-- V2.0 now standardizes on expanded enum sets with proper validation.
-- This migration restores database-level validation with the complete v2.0 enum values.
--
-- Changes:
-- 1. Restore CHECK constraints on status columns (projects, features, tasks)
-- 2. Reduce status VARCHAR from 50 back to 20 (sufficient for enum values)
-- 3. Add new enum values:
--    - TaskStatus: BACKLOG, IN_REVIEW, CHANGES_REQUESTED, ON_HOLD
--    - FeatureStatus: DRAFT, ON_HOLD
-- 4. Preserve all existing data (no transformations)
--
-- Complete Enum Sets (v2.0):
-- - ProjectStatus: PLANNING, IN_DEVELOPMENT, COMPLETED, ARCHIVED
-- - FeatureStatus: PLANNING, IN_DEVELOPMENT, COMPLETED, ARCHIVED, DRAFT, ON_HOLD
-- - TaskStatus: PENDING, IN_PROGRESS, COMPLETED, CANCELLED, DEFERRED, BACKLOG, IN_REVIEW, CHANGES_REQUESTED, ON_HOLD
--
-- Note: SQLite doesn't support ALTER TABLE to add CHECK constraints directly.
-- Solution: Create new tables with constraints, copy data, drop old, rename new.

-- ============================================================================
-- Projects Table Rebuild (with CHECK constraint)
-- ============================================================================

-- Create new projects table with status CHECK constraint
CREATE TABLE projects_new (
    id BLOB PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    summary TEXT NOT NULL,
    description TEXT,  -- Added in V6, preserved through V9
    status VARCHAR(20) NOT NULL CHECK (status IN ('PLANNING', 'IN_DEVELOPMENT', 'COMPLETED', 'ARCHIVED')),
    created_at TIMESTAMP NOT NULL,
    modified_at TIMESTAMP NOT NULL,
    search_vector TEXT,
    version BIGINT NOT NULL DEFAULT 1  -- Added in V3
);

-- Copy all data from old table
INSERT INTO projects_new (id, name, summary, description, status, created_at, modified_at, search_vector, version)
SELECT id, name, summary, description, status, created_at, modified_at, search_vector, version FROM projects;

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
-- Features Table Rebuild (with CHECK constraint + new DRAFT, ON_HOLD statuses)
-- ============================================================================

-- Create new features table with expanded status CHECK constraint
CREATE TABLE features_new (
    id BLOB PRIMARY KEY,
    project_id BLOB,
    name TEXT NOT NULL,
    summary TEXT NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('PLANNING', 'IN_DEVELOPMENT', 'COMPLETED', 'ARCHIVED', 'DRAFT', 'ON_HOLD')),
    priority VARCHAR(10) NOT NULL CHECK (priority IN ('HIGH', 'MEDIUM', 'LOW')),
    created_at TIMESTAMP NOT NULL,
    modified_at TIMESTAMP NOT NULL,
    search_vector TEXT,
    version INTEGER NOT NULL DEFAULT 1,  -- Added in V3
    description TEXT,  -- Added in V6
    FOREIGN KEY (project_id) REFERENCES projects(id)
);

-- Copy all data from old table
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
-- V8 composite indexes
CREATE INDEX idx_features_status_priority ON features(status, priority);
CREATE INDEX idx_features_project_status ON features(project_id, status);

-- ============================================================================
-- Tasks Table Rebuild (with CHECK constraint + new BACKLOG, IN_REVIEW, CHANGES_REQUESTED, ON_HOLD statuses)
-- ============================================================================

-- Create new tasks table with expanded status CHECK constraint
CREATE TABLE tasks_new (
    id BLOB PRIMARY KEY,
    project_id BLOB,
    feature_id BLOB,
    title TEXT NOT NULL,
    summary TEXT NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('PENDING', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED', 'DEFERRED', 'BACKLOG', 'IN_REVIEW', 'CHANGES_REQUESTED', 'ON_HOLD')),
    priority VARCHAR(20) NOT NULL CHECK (priority IN ('HIGH', 'MEDIUM', 'LOW')),
    complexity INTEGER NOT NULL,
    created_at TIMESTAMP NOT NULL,
    modified_at TIMESTAMP NOT NULL,
    version INTEGER NOT NULL DEFAULT 1,
    last_modified_by TEXT,
    lock_status VARCHAR(20) NOT NULL DEFAULT 'UNLOCKED' CHECK (lock_status IN ('UNLOCKED', 'LOCKED_EXCLUSIVE', 'LOCKED_SHARED', 'LOCKED_SECTION')),
    search_vector TEXT,
    description TEXT,  -- Added in V6
    FOREIGN KEY (project_id) REFERENCES projects(id),
    FOREIGN KEY (feature_id) REFERENCES features(id)
);

-- Copy all data from old table
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
-- V8 composite indexes
CREATE INDEX idx_tasks_status_priority_complexity ON tasks(status, priority, complexity);
CREATE INDEX idx_tasks_feature_status_priority ON tasks(feature_id, status, priority);

-- ============================================================================
-- Summary
-- ============================================================================
-- Migration complete. Status columns now:
-- - Have CHECK constraints with v2.0 expanded enum values
-- - Use VARCHAR(20) (sufficient for all enum values)
-- - All existing data preserved
-- - All indexes recreated
--
-- New enum values available:
-- - FeatureStatus: DRAFT, ON_HOLD (in addition to v1.0 values)
-- - TaskStatus: BACKLOG, IN_REVIEW, CHANGES_REQUESTED, ON_HOLD (in addition to v1.0 values)
-- - ProjectStatus: No changes (PLANNING, IN_DEVELOPMENT, COMPLETED, ARCHIVED)
