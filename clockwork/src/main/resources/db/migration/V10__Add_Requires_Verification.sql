-- Add verification gate flag to tasks
ALTER TABLE tasks ADD COLUMN requires_verification INTEGER NOT NULL DEFAULT 0;

-- Add verification gate flag to features
ALTER TABLE features ADD COLUMN requires_verification INTEGER NOT NULL DEFAULT 0;

-- Index for filtering entities that need verification
CREATE INDEX idx_tasks_requires_verification ON tasks(requires_verification);
CREATE INDEX idx_features_requires_verification ON features(requires_verification);
