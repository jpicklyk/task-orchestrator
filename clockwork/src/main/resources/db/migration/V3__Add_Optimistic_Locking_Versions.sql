-- V3: Add optimistic locking version columns to features, projects, and sections tables
-- Purpose: Enable optimistic locking for multi-agent concurrency control
-- Note: tasks table already has version column from V1

-- Add version column to features table
ALTER TABLE features ADD COLUMN version BIGINT NOT NULL DEFAULT 1;

-- Add version column to projects table
ALTER TABLE projects ADD COLUMN version BIGINT NOT NULL DEFAULT 1;

-- Add version column to sections table
ALTER TABLE sections ADD COLUMN version BIGINT NOT NULL DEFAULT 1;

-- Create indexes for version columns to support efficient version checking
CREATE INDEX idx_features_version ON features(version);
CREATE INDEX idx_projects_version ON projects(version);
CREATE INDEX idx_sections_version ON sections(version);

-- Rollback instructions (for manual rollback if needed):
-- DROP INDEX IF EXISTS idx_features_version;
-- DROP INDEX IF EXISTS idx_projects_version;
-- DROP INDEX IF EXISTS idx_sections_version;
-- ALTER TABLE features DROP COLUMN version;
-- ALTER TABLE projects DROP COLUMN version;
-- ALTER TABLE sections DROP COLUMN version;
