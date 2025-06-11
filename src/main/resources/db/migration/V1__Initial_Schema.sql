-- Initial schema migration for MCP Task Orchestrator
-- This creates all the tables that match the existing Exposed schema definitions

-- Projects table (no dependencies)
CREATE TABLE projects (
    id BLOB PRIMARY KEY DEFAULT (randomblob(16)),
    name VARCHAR(255) NOT NULL,
    summary TEXT NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('PLANNING', 'IN_DEVELOPMENT', 'COMPLETED', 'ARCHIVED')),
    created_at TIMESTAMP NOT NULL,
    modified_at TIMESTAMP NOT NULL,
    search_vector TEXT
);

-- Create indexes for projects
CREATE INDEX idx_projects_status ON projects(status);
CREATE INDEX idx_projects_created_at ON projects(created_at);
CREATE INDEX idx_projects_modified_at ON projects(modified_at);

-- Templates table (no dependencies)
CREATE TABLE templates (
    id BLOB PRIMARY KEY DEFAULT (randomblob(16)),
    name VARCHAR(200) NOT NULL UNIQUE,
    description TEXT NOT NULL,
    target_entity_type VARCHAR(50) NOT NULL,
    is_built_in BOOLEAN NOT NULL DEFAULT FALSE,
    is_protected BOOLEAN NOT NULL DEFAULT FALSE,
    is_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_by VARCHAR(200),
    tags TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    modified_at TIMESTAMP NOT NULL
);

-- Create indexes for templates
CREATE INDEX idx_templates_target_entity_type ON templates(target_entity_type);
CREATE INDEX idx_templates_is_built_in ON templates(is_built_in);
CREATE INDEX idx_templates_is_enabled ON templates(is_enabled);

-- Features table (depends on projects)
CREATE TABLE features (
    id BLOB PRIMARY KEY DEFAULT (randomblob(16)),
    project_id BLOB,
    name TEXT NOT NULL,
    summary TEXT NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('PLANNING', 'IN_DEVELOPMENT', 'COMPLETED', 'ARCHIVED')),
    priority VARCHAR(10) NOT NULL CHECK (priority IN ('HIGH', 'MEDIUM', 'LOW')),
    created_at TIMESTAMP NOT NULL,
    modified_at TIMESTAMP NOT NULL,
    search_vector TEXT,
    FOREIGN KEY (project_id) REFERENCES projects(id)
);

-- Create indexes for features
CREATE INDEX idx_features_project_id ON features(project_id);
CREATE INDEX idx_features_status ON features(status);
CREATE INDEX idx_features_priority ON features(priority);
CREATE INDEX idx_features_created_at ON features(created_at);
CREATE INDEX idx_features_modified_at ON features(modified_at);

-- Entity tags table (unified tags system)
CREATE TABLE entity_tags (
    id BLOB PRIMARY KEY DEFAULT (randomblob(16)),
    entity_id BLOB NOT NULL,
    entity_type VARCHAR(20) NOT NULL,
    tag VARCHAR(100) NOT NULL,
    created_at TIMESTAMP NOT NULL
);

-- Create indexes for entity_tags
CREATE UNIQUE INDEX idx_entity_tags_unique ON entity_tags(entity_id, entity_type, tag);
CREATE INDEX idx_entity_tags_tag ON entity_tags(tag);
CREATE INDEX idx_entity_tags_entity ON entity_tags(entity_id, entity_type);

-- Sections table (content blocks)
CREATE TABLE sections (
    id BLOB PRIMARY KEY DEFAULT (randomblob(16)),
    entity_type VARCHAR(50) NOT NULL,
    entity_id BLOB NOT NULL,
    title VARCHAR(200) NOT NULL,
    usage_description TEXT NOT NULL,
    content TEXT NOT NULL,
    content_format VARCHAR(50) NOT NULL,
    ordinal INTEGER NOT NULL,
    tags TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    modified_at TIMESTAMP NOT NULL
);

-- Create indexes for sections
CREATE INDEX idx_sections_entity ON sections(entity_type, entity_id);
CREATE INDEX idx_sections_entity_id ON sections(entity_id);
CREATE UNIQUE INDEX idx_sections_entity_ordinal ON sections(entity_type, entity_id, ordinal);

-- Template sections table (depends on templates)
CREATE TABLE template_sections (
    id BLOB PRIMARY KEY DEFAULT (randomblob(16)),
    template_id BLOB NOT NULL,
    title VARCHAR(200) NOT NULL,
    usage_description TEXT NOT NULL,
    content_sample TEXT NOT NULL,
    content_format VARCHAR(50) NOT NULL,
    ordinal INTEGER NOT NULL,
    is_required BOOLEAN NOT NULL DEFAULT FALSE,
    tags TEXT NOT NULL,
    FOREIGN KEY (template_id) REFERENCES templates(id)
);

-- Create indexes for template_sections
CREATE INDEX idx_template_sections_template_id ON template_sections(template_id);
CREATE UNIQUE INDEX idx_template_sections_template_ordinal ON template_sections(template_id, ordinal);

-- Tasks table (depends on projects and features)
CREATE TABLE tasks (
    id BLOB PRIMARY KEY DEFAULT (randomblob(16)),
    project_id BLOB,
    feature_id BLOB,
    title TEXT NOT NULL,
    summary TEXT NOT NULL,
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

-- Create indexes for tasks
CREATE INDEX idx_tasks_project_id ON tasks(project_id);
CREATE INDEX idx_tasks_feature_id ON tasks(feature_id);
CREATE INDEX idx_tasks_status ON tasks(status);
CREATE INDEX idx_tasks_priority ON tasks(priority);
CREATE INDEX idx_tasks_version ON tasks(version);
CREATE INDEX idx_tasks_lock_status ON tasks(lock_status);
CREATE INDEX idx_tasks_last_modified_by ON tasks(last_modified_by);

-- Dependencies table (depends on tasks)
CREATE TABLE dependencies (
    id BLOB PRIMARY KEY DEFAULT (randomblob(16)),
    from_task_id BLOB NOT NULL,
    to_task_id BLOB NOT NULL,
    type VARCHAR(20) NOT NULL CHECK (type IN ('BLOCKS', 'IS_BLOCKED_BY', 'RELATES_TO')),
    created_at TIMESTAMP NOT NULL,
    FOREIGN KEY (from_task_id) REFERENCES tasks(id),
    FOREIGN KEY (to_task_id) REFERENCES tasks(id)
);

-- Create indexes for dependencies
CREATE UNIQUE INDEX idx_dependencies_unique ON dependencies(from_task_id, to_task_id, type);

-- Work sessions table (locking system)
CREATE TABLE work_sessions (
    session_id VARCHAR(255) PRIMARY KEY,
    client_id VARCHAR(100) NOT NULL,
    client_version VARCHAR(50) NOT NULL,
    hostname VARCHAR(255),
    user_context VARCHAR(255),
    started_at TIMESTAMP NOT NULL,
    last_activity TIMESTAMP NOT NULL,
    capabilities TEXT NOT NULL,
    git_worktree_info TEXT,
    active_assignments TEXT,
    metadata TEXT,
    created_at TIMESTAMP NOT NULL
);

-- Create indexes for work_sessions
CREATE INDEX idx_work_sessions_last_activity ON work_sessions(last_activity);
CREATE INDEX idx_work_sessions_client_id ON work_sessions(client_id);
CREATE INDEX idx_work_sessions_started_at ON work_sessions(started_at);

-- Task locks table (depends on work_sessions)
CREATE TABLE task_locks (
    id BLOB PRIMARY KEY DEFAULT (randomblob(16)),
    lock_scope VARCHAR(20) NOT NULL CHECK (lock_scope IN ('PROJECT', 'FEATURE', 'TASK', 'SECTION')),
    entity_id BLOB NOT NULL,
    session_id VARCHAR(255) NOT NULL,
    lock_type VARCHAR(20) NOT NULL CHECK (lock_type IN ('EXCLUSIVE', 'SHARED_READ', 'SHARED_WRITE', 'SECTION_WRITE', 'NONE')),
    locked_at TIMESTAMP NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    last_renewed TIMESTAMP NOT NULL,
    lock_context TEXT NOT NULL,
    affected_entities TEXT,
    created_at TIMESTAMP NOT NULL,
    FOREIGN KEY (session_id) REFERENCES work_sessions(session_id)
);

-- Create indexes for task_locks
CREATE INDEX idx_task_locks_scope_entity ON task_locks(lock_scope, entity_id);
CREATE INDEX idx_task_locks_session_id ON task_locks(session_id);
CREATE INDEX idx_task_locks_expires_at ON task_locks(expires_at);
CREATE INDEX idx_task_locks_affected_entities ON task_locks(affected_entities);
CREATE INDEX idx_task_locks_lock_type ON task_locks(lock_type);
CREATE INDEX idx_task_locks_locked_at ON task_locks(locked_at);

-- Entity assignments table (depends on work_sessions)
CREATE TABLE entity_assignments (
    id BLOB PRIMARY KEY DEFAULT (randomblob(16)),
    entity_type VARCHAR(20) NOT NULL CHECK (entity_type IN ('TASK', 'FEATURE', 'PROJECT')),
    entity_id BLOB NOT NULL,
    session_id VARCHAR(255) NOT NULL,
    assignment_type VARCHAR(20) NOT NULL CHECK (assignment_type IN ('EXCLUSIVE', 'COLLABORATIVE')),
    assigned_at TIMESTAMP NOT NULL,
    expires_at TIMESTAMP,
    estimated_completion TIMESTAMP,
    git_branch VARCHAR(255),
    progress_metadata TEXT,
    created_at TIMESTAMP NOT NULL,
    FOREIGN KEY (session_id) REFERENCES work_sessions(session_id)
);

-- Create indexes for entity_assignments
CREATE INDEX idx_entity_assignments_entity ON entity_assignments(entity_type, entity_id);
CREATE INDEX idx_entity_assignments_session_id ON entity_assignments(session_id);
CREATE INDEX idx_entity_assignments_expires_at ON entity_assignments(expires_at);
CREATE INDEX idx_entity_assignments_assignment_type ON entity_assignments(assignment_type);
CREATE INDEX idx_entity_assignments_git_branch ON entity_assignments(git_branch);