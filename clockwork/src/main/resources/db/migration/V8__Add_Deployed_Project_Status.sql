-- V8: Add DEPLOYED status to projects table
-- Expands project status enum: 6 → 7 statuses (+DEPLOYED)
-- SQLite requires table recreation to modify CHECK constraints

CREATE TABLE projects_new (
    id BLOB PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    summary TEXT NOT NULL,
    description TEXT,
    status VARCHAR(20) NOT NULL CHECK (status IN ('PLANNING', 'IN_DEVELOPMENT', 'ON_HOLD', 'DEPLOYED', 'CANCELLED', 'COMPLETED', 'ARCHIVED')),
    version INTEGER NOT NULL DEFAULT 1,
    created_at TIMESTAMP NOT NULL,
    modified_at TIMESTAMP NOT NULL,
    search_vector TEXT
);

INSERT INTO projects_new SELECT id, name, summary, description, status, version, created_at, modified_at, search_vector FROM projects;
DROP TABLE projects;
ALTER TABLE projects_new RENAME TO projects;

-- Recreate indexes
CREATE INDEX idx_projects_status ON projects(status);
CREATE INDEX idx_projects_created_at ON projects(created_at);
CREATE INDEX idx_projects_modified_at ON projects(modified_at);
CREATE INDEX idx_projects_version ON projects(version);
CREATE INDEX idx_projects_search_vector ON projects(search_vector);
