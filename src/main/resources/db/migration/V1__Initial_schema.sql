CREATE TABLE IF NOT EXISTS projects (
    id VARCHAR(36) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    summary TEXT NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    modified_at TIMESTAMP NOT NULL,
    search_vector TEXT
);

CREATE TABLE IF NOT EXISTS templates (
    id VARCHAR(36) PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    description VARCHAR(500) NOT NULL,
    is_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL,
    modified_at TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS features (
    id VARCHAR(36) PRIMARY KEY,
    project_id VARCHAR(36) NOT NULL,
    name VARCHAR(100) NOT NULL,
    summary VARCHAR(500) NOT NULL,
    status INTEGER NOT NULL,
    priority INTEGER NOT NULL,
    created_at TIMESTAMP NOT NULL,
    modified_at TIMESTAMP NOT NULL,
    FOREIGN KEY (project_id) REFERENCES projects(id)
);

CREATE TABLE IF NOT EXISTS entity_tags (
    id VARCHAR(36) PRIMARY KEY,
    entity_type VARCHAR(50) NOT NULL,
    entity_id VARCHAR(36) NOT NULL,
    tag VARCHAR(50) NOT NULL,
    created_at TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS sections (
    id VARCHAR(36) PRIMARY KEY,
    entity_type VARCHAR(50) NOT NULL,
    entity_id VARCHAR(36) NOT NULL,
    title VARCHAR(100) NOT NULL,
    content TEXT NOT NULL,
    order_index INTEGER NOT NULL,
    created_at TIMESTAMP NOT NULL,
    modified_at TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS template_sections (
    id VARCHAR(36) PRIMARY KEY,
    template_id VARCHAR(36) NOT NULL,
    title VARCHAR(100) NOT NULL,
    content TEXT NOT NULL,
    order_index INTEGER NOT NULL,
    created_at TIMESTAMP NOT NULL,
    modified_at TIMESTAMP NOT NULL,
    FOREIGN KEY (template_id) REFERENCES templates(id)
);

CREATE TABLE IF NOT EXISTS tasks (
    id VARCHAR(36) PRIMARY KEY,
    project_id VARCHAR(36) NOT NULL,
    feature_id VARCHAR(36) NOT NULL,
    title VARCHAR(200) NOT NULL,
    summary VARCHAR(500) NOT NULL,
    status INTEGER NOT NULL,
    priority INTEGER NOT NULL,
    complexity INTEGER NOT NULL DEFAULT 3,
    created_at TIMESTAMP NOT NULL,
    modified_at TIMESTAMP NOT NULL,
    FOREIGN KEY (project_id) REFERENCES projects(id),
    FOREIGN KEY (feature_id) REFERENCES features(id)
);

-- Create indexes for optimized queries
CREATE INDEX IF NOT EXISTS idx_projects_status ON projects(status);
CREATE INDEX IF NOT EXISTS idx_projects_created_at ON projects(created_at);
CREATE INDEX IF NOT EXISTS idx_projects_modified_at ON projects(modified_at);

CREATE INDEX IF NOT EXISTS idx_features_project_id ON features(project_id);
CREATE INDEX IF NOT EXISTS idx_features_status ON features(status);
CREATE INDEX IF NOT EXISTS idx_features_priority ON features(priority);

CREATE INDEX IF NOT EXISTS idx_entity_tags_entity_id ON entity_tags(entity_id);
CREATE INDEX IF NOT EXISTS idx_entity_tags_entity_type ON entity_tags(entity_type);
CREATE INDEX IF NOT EXISTS idx_entity_tags_tag ON entity_tags(tag);

CREATE INDEX IF NOT EXISTS idx_sections_entity_id ON sections(entity_id);
CREATE INDEX IF NOT EXISTS idx_sections_entity_type ON sections(entity_type);
CREATE INDEX IF NOT EXISTS idx_sections_order_index ON sections(order_index);

CREATE INDEX IF NOT EXISTS idx_template_sections_template_id ON template_sections(template_id);
CREATE INDEX IF NOT EXISTS idx_template_sections_order_index ON template_sections(order_index);

CREATE INDEX IF NOT EXISTS idx_tasks_project_id ON tasks(project_id);
CREATE INDEX IF NOT EXISTS idx_tasks_feature_id ON tasks(feature_id);
CREATE INDEX IF NOT EXISTS idx_tasks_status ON tasks(status);
CREATE INDEX IF NOT EXISTS idx_tasks_priority ON tasks(priority);
CREATE INDEX IF NOT EXISTS idx_tasks_complexity ON tasks(complexity);

CREATE TABLE IF NOT EXISTS dependencies (
    id VARCHAR(36) PRIMARY KEY,
    from_task_id VARCHAR(36) NOT NULL,
    to_task_id VARCHAR(36) NOT NULL,
    type VARCHAR(20) NOT NULL CHECK (type IN ('BLOCKS', 'IS_BLOCKED_BY', 'RELATES_TO')),
    created_at TIMESTAMP NOT NULL,
    FOREIGN KEY (from_task_id) REFERENCES tasks(id) ON DELETE CASCADE,
    FOREIGN KEY (to_task_id) REFERENCES tasks(id) ON DELETE CASCADE,
    UNIQUE(from_task_id, to_task_id, type)
);

-- Create indexes for dependencies table
CREATE INDEX IF NOT EXISTS idx_dependencies_from_task ON dependencies(from_task_id);
CREATE INDEX IF NOT EXISTS idx_dependencies_to_task ON dependencies(to_task_id);
CREATE INDEX IF NOT EXISTS idx_dependencies_type ON dependencies(type);
