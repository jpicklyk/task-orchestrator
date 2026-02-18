-- V14: Add role_transitions table for tracking role change audit trail
CREATE TABLE IF NOT EXISTS role_transitions (
    id TEXT PRIMARY KEY,
    entity_id TEXT NOT NULL,
    entity_type TEXT NOT NULL CHECK(entity_type IN ('task', 'feature', 'project')),
    from_role TEXT NOT NULL,
    to_role TEXT NOT NULL,
    from_status TEXT NOT NULL,
    to_status TEXT NOT NULL,
    transitioned_at TEXT NOT NULL,
    trigger TEXT,
    summary TEXT
);

-- Index for querying transitions by entity
CREATE INDEX IF NOT EXISTS idx_role_transitions_entity_id ON role_transitions(entity_id);

-- Index for time-range queries
CREATE INDEX IF NOT EXISTS idx_role_transitions_transitioned_at ON role_transitions(transitioned_at);

-- Composite index for entity + time range queries
CREATE INDEX IF NOT EXISTS idx_role_transitions_entity_time ON role_transitions(entity_id, transitioned_at);
