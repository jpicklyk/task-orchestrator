-- V10: Fix missing description field in projects table
-- Bug: V9 migration forgot to include description column when rebuilding projects table
-- V6 and V7 added description (nullable) to all container types, but V9 omitted it for projects
--
-- This migration adds description back to projects table to match features and tasks

ALTER TABLE projects ADD COLUMN description TEXT;

-- Description is nullable (as per V7), so no default value or data migration needed
-- Existing projects will have NULL description, which is acceptable
