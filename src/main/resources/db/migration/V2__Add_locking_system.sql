-- Add locking-related columns to existing tasks table
ALTER TABLE tasks ADD COLUMN version INTEGER DEFAULT 1;
ALTER TABLE tasks ADD COLUMN last_modified_by VARCHAR(255);
ALTER TABLE tasks ADD COLUMN lock_status VARCHAR(20) DEFAULT 'UNLOCKED';

-- Create indexes for new task columns
CREATE INDEX IF NOT EXISTS idx_tasks_version ON tasks(version);
CREATE INDEX IF NOT EXISTS idx_tasks_lock_status ON tasks(lock_status);
CREATE INDEX IF NOT EXISTS idx_tasks_last_modified_by ON tasks(last_modified_by);

-- Create work_sessions table
CREATE TABLE IF NOT EXISTS work_sessions (
    session_id VARCHAR(255) PRIMARY KEY,
    client_id VARCHAR(100) NOT NULL,
    client_version VARCHAR(50) NOT NULL,
    hostname VARCHAR(255),
    user_context VARCHAR(255),
    started_at TIMESTAMP NOT NULL,
    last_activity TIMESTAMP NOT NULL,
    capabilities TEXT NOT NULL,  -- JSON array
    git_worktree_info TEXT,      -- JSON with worktree details
    active_assignments TEXT,     -- JSON with current assignments by scope
    metadata TEXT,               -- JSON
    created_at TIMESTAMP NOT NULL
);

-- Create indexes for work_sessions
CREATE INDEX IF NOT EXISTS idx_work_sessions_last_activity ON work_sessions(last_activity);
CREATE INDEX IF NOT EXISTS idx_work_sessions_client_id ON work_sessions(client_id);
CREATE INDEX IF NOT EXISTS idx_work_sessions_started_at ON work_sessions(started_at);

-- Create task_locks table
CREATE TABLE IF NOT EXISTS task_locks (
    id VARCHAR(36) PRIMARY KEY,
    lock_scope VARCHAR(20) NOT NULL CHECK (lock_scope IN ('PROJECT', 'FEATURE', 'TASK', 'SECTION')),
    entity_id VARCHAR(36) NOT NULL,
    session_id VARCHAR(255) NOT NULL,
    lock_type VARCHAR(20) NOT NULL CHECK (lock_type IN ('EXCLUSIVE', 'SHARED_READ', 'SHARED_WRITE', 'SECTION_WRITE', 'NONE')),
    locked_at TIMESTAMP NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    last_renewed TIMESTAMP NOT NULL,
    lock_context TEXT NOT NULL,     -- JSON with operation, git context, assignment type
    affected_entities TEXT,         -- JSON array of all affected entity IDs
    created_at TIMESTAMP NOT NULL,
    
    FOREIGN KEY (session_id) REFERENCES work_sessions(session_id) ON DELETE CASCADE
);

-- Create indexes for task_locks (hierarchical queries)
CREATE INDEX IF NOT EXISTS idx_task_locks_scope_entity ON task_locks(lock_scope, entity_id);
CREATE INDEX IF NOT EXISTS idx_task_locks_session_id ON task_locks(session_id);
CREATE INDEX IF NOT EXISTS idx_task_locks_expires_at ON task_locks(expires_at);
CREATE INDEX IF NOT EXISTS idx_task_locks_affected_entities ON task_locks(affected_entities);
CREATE INDEX IF NOT EXISTS idx_task_locks_lock_type ON task_locks(lock_type);
CREATE INDEX IF NOT EXISTS idx_task_locks_locked_at ON task_locks(locked_at);

-- Create entity_assignments table
CREATE TABLE IF NOT EXISTS entity_assignments (
    id VARCHAR(36) PRIMARY KEY,
    entity_type VARCHAR(20) NOT NULL CHECK (entity_type IN ('PROJECT', 'FEATURE', 'TASK')),
    entity_id VARCHAR(36) NOT NULL,
    session_id VARCHAR(255) NOT NULL,
    assignment_type VARCHAR(20) NOT NULL CHECK (assignment_type IN ('EXCLUSIVE', 'COLLABORATIVE')),
    assigned_at TIMESTAMP NOT NULL,
    expires_at TIMESTAMP,
    estimated_completion TIMESTAMP,
    git_branch VARCHAR(255),                -- Associated git branch
    progress_metadata TEXT,                 -- JSON with progress tracking
    created_at TIMESTAMP NOT NULL,
    
    FOREIGN KEY (session_id) REFERENCES work_sessions(session_id) ON DELETE CASCADE
);

-- Create indexes for entity_assignments
CREATE INDEX IF NOT EXISTS idx_entity_assignments_entity ON entity_assignments(entity_type, entity_id);
CREATE INDEX IF NOT EXISTS idx_entity_assignments_session ON entity_assignments(session_id);
CREATE INDEX IF NOT EXISTS idx_entity_assignments_expires ON entity_assignments(expires_at);
CREATE INDEX IF NOT EXISTS idx_entity_assignments_assignment_type ON entity_assignments(assignment_type);
CREATE INDEX IF NOT EXISTS idx_entity_assignments_git_branch ON entity_assignments(git_branch);

-- Update existing tasks to allow nullable project_id and feature_id (some schemas might require this)
-- This is safe as it only relaxes constraints
UPDATE tasks SET project_id = NULL WHERE project_id = '';
UPDATE tasks SET feature_id = NULL WHERE feature_id = '';