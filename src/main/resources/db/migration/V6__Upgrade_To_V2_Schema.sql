-- V6: Upgrade from v1.0.1 (V5) to v2.0.0 schema
-- This migration adds v2.0.0 enhancements on top of v1.0.1's V3, V4, V5 migrations.
--
-- Prerequisites (already applied in v1.0.1):
-- - V3: Optimistic locking (version columns)
-- - V4: Basic performance indexes
-- - V5: Template content updates
--
-- Changes Applied in V6:
-- 1. Add description fields to projects, features, and tasks (separates "what to do" from "what was done")
-- 2. Add additional performance indexes for complex queries
-- 3. Expand status enums to v2.0 values (6 project, 11 feature, 14 task statuses)
--
-- Supports upgrade from v1.0.1 (V5) → v2.0.0 (V6)

-- =============================================================================
-- STEP 1: Add Description Fields (separate from summary)
-- =============================================================================
-- Purpose: Separate user-provided "what to do" (description) from
-- agent-generated "what was done" (summary)

-- Add description to projects
ALTER TABLE projects ADD COLUMN description TEXT;

-- Add description to features
ALTER TABLE features ADD COLUMN description TEXT;

-- Add description to tasks
ALTER TABLE tasks ADD COLUMN description TEXT;

-- =============================================================================
-- STEP 2: Add Additional Performance Indexes
-- =============================================================================
-- These complement the indexes already added in V4

-- Task composite indexes for complex filtering
CREATE INDEX IF NOT EXISTS idx_tasks_project_feature ON tasks(project_id, feature_id);
CREATE INDEX IF NOT EXISTS idx_tasks_status_priority_complexity ON tasks(status, priority, complexity);
CREATE INDEX IF NOT EXISTS idx_tasks_feature_status_priority ON tasks(feature_id, status, priority);

-- Feature composite indexes for filtering
CREATE INDEX IF NOT EXISTS idx_features_status_priority ON features(status, priority);
CREATE INDEX IF NOT EXISTS idx_features_project_status ON features(project_id, status);

-- =============================================================================
-- STEP 3: Expand Status Enums to v2.0 Values
-- SQLite doesn't support ALTER TABLE for CHECK constraints, so we rebuild tables
-- =============================================================================

-- Rebuild projects table with expanded status enum (4 → 6 statuses)
-- v1.0.1: PLANNING, IN_DEVELOPMENT, COMPLETED, ARCHIVED
-- v2.0.0: +ON_HOLD, +CANCELLED
CREATE TABLE projects_new (
    id BLOB PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    summary TEXT NOT NULL,
    description TEXT,
    status VARCHAR(20) NOT NULL CHECK (status IN ('PLANNING', 'IN_DEVELOPMENT', 'ON_HOLD', 'CANCELLED', 'COMPLETED', 'ARCHIVED')),
    version INTEGER NOT NULL DEFAULT 1,
    created_at TIMESTAMP NOT NULL,
    modified_at TIMESTAMP NOT NULL,
    search_vector TEXT
);

INSERT INTO projects_new SELECT id, name, summary, description, status, version, created_at, modified_at, search_vector FROM projects;
DROP TABLE projects;
ALTER TABLE projects_new RENAME TO projects;

-- Recreate projects indexes
CREATE INDEX idx_projects_status ON projects(status);
CREATE INDEX idx_projects_created_at ON projects(created_at);
CREATE INDEX idx_projects_modified_at ON projects(modified_at);
CREATE INDEX idx_projects_version ON projects(version);
CREATE INDEX idx_projects_search_vector ON projects(search_vector);

-- Rebuild features table with expanded status enum (4 → 11 statuses)
-- v1.0.1: PLANNING, IN_DEVELOPMENT, COMPLETED, ARCHIVED
-- v2.0.0: +DRAFT, +TESTING, +VALIDATING, +PENDING_REVIEW, +BLOCKED, +ON_HOLD, +DEPLOYED
CREATE TABLE features_new (
    id BLOB PRIMARY KEY,
    project_id BLOB,
    name TEXT NOT NULL,
    summary TEXT NOT NULL,
    description TEXT,
    status VARCHAR(20) NOT NULL CHECK (status IN ('DRAFT', 'PLANNING', 'IN_DEVELOPMENT', 'TESTING', 'VALIDATING', 'PENDING_REVIEW', 'BLOCKED', 'ON_HOLD', 'DEPLOYED', 'COMPLETED', 'ARCHIVED')),
    priority VARCHAR(10) NOT NULL CHECK (priority IN ('HIGH', 'MEDIUM', 'LOW')),
    version INTEGER NOT NULL DEFAULT 1,
    created_at TIMESTAMP NOT NULL,
    modified_at TIMESTAMP NOT NULL,
    search_vector TEXT,
    FOREIGN KEY (project_id) REFERENCES projects(id)
);

INSERT INTO features_new SELECT id, project_id, name, summary, description, status, priority, version, created_at, modified_at, search_vector FROM features;
DROP TABLE features;
ALTER TABLE features_new RENAME TO features;

-- Recreate features indexes
CREATE INDEX idx_features_project_id ON features(project_id);
CREATE INDEX idx_features_status ON features(status);
CREATE INDEX idx_features_priority ON features(priority);
CREATE INDEX idx_features_created_at ON features(created_at);
CREATE INDEX idx_features_modified_at ON features(modified_at);
CREATE INDEX idx_features_version ON features(version);
CREATE INDEX idx_features_search_vector ON features(search_vector);
-- V6 additional composite indexes
CREATE INDEX idx_features_status_priority ON features(status, priority);
CREATE INDEX idx_features_project_status ON features(project_id, status);

-- Rebuild tasks table with expanded status enum (5 → 14 statuses)
-- v1.0.1: PENDING, IN_PROGRESS, COMPLETED, CANCELLED, DEFERRED
-- v2.0.0: +BACKLOG, +IN_REVIEW, +CHANGES_REQUESTED, +TESTING, +READY_FOR_QA, +INVESTIGATING, +BLOCKED, +ON_HOLD, +DEPLOYED
CREATE TABLE tasks_new (
    id BLOB PRIMARY KEY,
    project_id BLOB,
    feature_id BLOB,
    title TEXT NOT NULL,
    summary TEXT NOT NULL,
    description TEXT,
    status VARCHAR(20) NOT NULL CHECK (status IN ('BACKLOG', 'PENDING', 'IN_PROGRESS', 'IN_REVIEW', 'CHANGES_REQUESTED', 'TESTING', 'READY_FOR_QA', 'INVESTIGATING', 'BLOCKED', 'ON_HOLD', 'DEPLOYED', 'COMPLETED', 'CANCELLED', 'DEFERRED')),
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

-- Recreate tasks indexes
CREATE INDEX idx_tasks_project_id ON tasks(project_id);
CREATE INDEX idx_tasks_feature_id ON tasks(feature_id);
CREATE INDEX idx_tasks_status ON tasks(status);
CREATE INDEX idx_tasks_priority ON tasks(priority);
CREATE INDEX idx_tasks_version ON tasks(version);
CREATE INDEX idx_tasks_lock_status ON tasks(lock_status);
CREATE INDEX idx_tasks_last_modified_by ON tasks(last_modified_by);
CREATE INDEX idx_tasks_search_vector ON tasks(search_vector);
-- V4 indexes
CREATE INDEX idx_tasks_feature_status ON tasks(feature_id, status);
CREATE INDEX idx_tasks_project_status ON tasks(project_id, status);
CREATE INDEX idx_tasks_status_priority ON tasks(status, priority);
CREATE INDEX idx_tasks_priority_created ON tasks(priority DESC, created_at ASC);
-- V6 additional composite indexes
CREATE INDEX idx_tasks_project_feature ON tasks(project_id, feature_id);
CREATE INDEX idx_tasks_status_priority_complexity ON tasks(status, priority, complexity);
CREATE INDEX idx_tasks_feature_status_priority ON tasks(feature_id, status, priority);

-- =============================================================================
-- Migration Complete
-- =============================================================================
-- v2.0.0 schema fully applied
-- - 31 total status values across all entity types (6 project, 11 feature, 14 task)
-- - Description fields added to all container entities
-- - Additional performance indexes for complex queries
-- - Templates already initialized in V5 (no re-initialization needed)
