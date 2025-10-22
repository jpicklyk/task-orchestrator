-- V12: Add complete v2.0 status system support
--
-- Background: V11 added initial v2.0 status expansions but was missing several new statuses
-- defined in the Kotlin enums and default-config.yaml. This migration completes the
-- v2.0 status system by adding all 11 new statuses.
--
-- Changes:
-- 1. Add missing v2.0 status values to CHECK constraints:
--    Tasks: READY_FOR_QA, INVESTIGATING, DEPLOYED, TESTING (4 new)
--    Features: TESTING, VALIDATING, PENDING_REVIEW, BLOCKED, DEPLOYED (5 new)
--    Projects: CANCELLED (already added), ON_HOLD (1 new - moved from emergency to allowed)
-- 2. Preserve VARCHAR(20) for status columns (sufficient for all values)
-- 3. Preserve all existing data (no transformations)
--
-- Complete Status Sets (v2.0 Final):
-- - ProjectStatus (6 total): PLANNING, IN_DEVELOPMENT, ON_HOLD, CANCELLED, COMPLETED, ARCHIVED
-- - FeatureStatus (11 total): DRAFT, PLANNING, IN_DEVELOPMENT, TESTING, VALIDATING,
--                              PENDING_REVIEW, BLOCKED, ON_HOLD, DEPLOYED, COMPLETED, ARCHIVED
-- - TaskStatus (14 total): BACKLOG, PENDING, IN_PROGRESS, IN_REVIEW, CHANGES_REQUESTED,
--                           TESTING, READY_FOR_QA, INVESTIGATING, BLOCKED, ON_HOLD,
--                           DEPLOYED, COMPLETED, CANCELLED, DEFERRED
--
-- Migration Strategy:
-- SQLite doesn't support ALTER TABLE to modify CHECK constraints.
-- Solution: Create new tables with expanded constraints, copy data, drop old, rename new.

-- ============================================================================
-- Projects Table Rebuild (add CANCELLED, make ON_HOLD explicit)
-- ============================================================================

-- Create new projects table with expanded status CHECK constraint (6 statuses)
CREATE TABLE projects_new (
    id BLOB PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    summary TEXT NOT NULL,
    description TEXT,
    status VARCHAR(20) NOT NULL CHECK (status IN (
        'PLANNING',
        'IN_DEVELOPMENT',
        'ON_HOLD',        -- v2.0: Project temporarily paused
        'CANCELLED',      -- v2.0: Project cancelled/abandoned
        'COMPLETED',
        'ARCHIVED'
    )),
    created_at TIMESTAMP NOT NULL,
    modified_at TIMESTAMP NOT NULL,
    search_vector TEXT,
    version BIGINT NOT NULL DEFAULT 1
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
CREATE INDEX idx_projects_version ON projects(version);

-- ============================================================================
-- Features Table Rebuild (add TESTING, VALIDATING, PENDING_REVIEW, BLOCKED, DEPLOYED)
-- ============================================================================

-- Create new features table with expanded status CHECK constraint (11 statuses)
CREATE TABLE features_new (
    id BLOB PRIMARY KEY,
    project_id BLOB,
    name TEXT NOT NULL,
    summary TEXT NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN (
        'DRAFT',          -- v2.0: Initial draft state
        'PLANNING',
        'IN_DEVELOPMENT',
        'TESTING',        -- v2.0: Feature in testing phase
        'VALIDATING',     -- v2.0: Tests passed, final validation
        'PENDING_REVIEW', -- v2.0: Awaiting human review approval
        'BLOCKED',        -- v2.0: Blocked by external dependencies
        'ON_HOLD',        -- v2.0: Feature temporarily paused
        'DEPLOYED',       -- v2.0: Successfully deployed to production
        'COMPLETED',
        'ARCHIVED'
    )),
    priority VARCHAR(10) NOT NULL CHECK (priority IN ('HIGH', 'MEDIUM', 'LOW')),
    created_at TIMESTAMP NOT NULL,
    modified_at TIMESTAMP NOT NULL,
    search_vector TEXT,
    version INTEGER NOT NULL DEFAULT 1,
    description TEXT,
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
CREATE INDEX idx_features_version ON features(version);
-- V8 composite indexes
CREATE INDEX idx_features_status_priority ON features(status, priority);
CREATE INDEX idx_features_project_status ON features(project_id, status);

-- ============================================================================
-- Tasks Table Rebuild (add READY_FOR_QA, INVESTIGATING, DEPLOYED, TESTING)
-- ============================================================================

-- Create new tasks table with expanded status CHECK constraint (14 statuses)
CREATE TABLE tasks_new (
    id BLOB PRIMARY KEY,
    project_id BLOB,
    feature_id BLOB,
    title TEXT NOT NULL,
    summary TEXT NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN (
        'BACKLOG',           -- v2.0: Task in backlog
        'PENDING',
        'IN_PROGRESS',
        'IN_REVIEW',         -- v2.0: Awaiting code review
        'CHANGES_REQUESTED', -- v2.0: Changes requested from review
        'TESTING',           -- v2.0: Running test suite
        'READY_FOR_QA',      -- v2.0: Testing complete, ready for QA review
        'INVESTIGATING',     -- v2.0: Actively investigating issues
        'BLOCKED',           -- v2.0: Blocked by incomplete dependencies
        'ON_HOLD',           -- v2.0: Task temporarily paused
        'DEPLOYED',          -- v2.0: Successfully deployed to production
        'COMPLETED',
        'CANCELLED',
        'DEFERRED'
    )),
    priority VARCHAR(20) NOT NULL CHECK (priority IN ('HIGH', 'MEDIUM', 'LOW')),
    complexity INTEGER NOT NULL,
    created_at TIMESTAMP NOT NULL,
    modified_at TIMESTAMP NOT NULL,
    version INTEGER NOT NULL DEFAULT 1,
    last_modified_by TEXT,
    lock_status VARCHAR(20) NOT NULL DEFAULT 'UNLOCKED' CHECK (lock_status IN ('UNLOCKED', 'LOCKED_EXCLUSIVE', 'LOCKED_SHARED', 'LOCKED_SECTION')),
    search_vector TEXT,
    description TEXT,
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
-- Migration complete. v2.0 status system fully implemented:
--
-- Projects (6 statuses):
--   - Added: CANCELLED, ON_HOLD (moved from emergency to standard)
--
-- Features (11 statuses):
--   - Previously had: PLANNING, IN_DEVELOPMENT, COMPLETED, ARCHIVED, DRAFT, ON_HOLD (6)
--   - Added: TESTING, VALIDATING, PENDING_REVIEW, BLOCKED, DEPLOYED (5 new)
--
-- Tasks (14 statuses):
--   - Previously had: PENDING, IN_PROGRESS, COMPLETED, CANCELLED, DEFERRED,
--                     BACKLOG, IN_REVIEW, CHANGES_REQUESTED, ON_HOLD (9)
--   - Added: TESTING, READY_FOR_QA, INVESTIGATING, BLOCKED, DEPLOYED (5 new)
--
-- All data preserved, all indexes recreated.
-- Database schema now matches Kotlin enums and default-config.yaml exactly.
