-- Current (v3) Initial Schema
-- 4 tables: work_items, notes, dependencies, role_transitions

-- WorkItems table (self-referencing hierarchy)
CREATE TABLE work_items (
    id              BLOB PRIMARY KEY DEFAULT (randomblob(16)),
    parent_id       BLOB REFERENCES work_items(id),
    title           TEXT NOT NULL,
    description     TEXT,
    summary         TEXT NOT NULL DEFAULT '',
    role            TEXT NOT NULL DEFAULT 'queue'
                    CHECK (role IN ('queue', 'work', 'review', 'blocked', 'terminal')),
    status_label    TEXT,
    previous_role   TEXT CHECK (previous_role IS NULL OR previous_role IN ('queue', 'work', 'review', 'blocked', 'terminal')),
    priority        TEXT NOT NULL DEFAULT 'medium'
                    CHECK (priority IN ('high', 'medium', 'low')),
    complexity      INTEGER NOT NULL DEFAULT 5,
    depth           INTEGER NOT NULL DEFAULT 0,
    metadata        TEXT,
    tags            TEXT,
    created_at      TIMESTAMP NOT NULL,
    modified_at     TIMESTAMP NOT NULL,
    role_changed_at TIMESTAMP NOT NULL,
    version         INTEGER NOT NULL DEFAULT 1
);

CREATE INDEX idx_work_items_parent ON work_items(parent_id);
CREATE INDEX idx_work_items_role ON work_items(role);
CREATE INDEX idx_work_items_depth ON work_items(depth);
CREATE INDEX idx_work_items_priority ON work_items(priority);
CREATE INDEX idx_work_items_role_changed ON work_items(role, role_changed_at);

-- Notes table (accountability artifacts attached to work items)
CREATE TABLE notes (
    id              BLOB PRIMARY KEY DEFAULT (randomblob(16)),
    work_item_id    BLOB NOT NULL REFERENCES work_items(id) ON DELETE CASCADE,
    key             VARCHAR(200) NOT NULL,
    role            VARCHAR(20) NOT NULL,
    body            TEXT NOT NULL DEFAULT '',
    created_at      TIMESTAMP NOT NULL,
    modified_at     TIMESTAMP NOT NULL
);

CREATE UNIQUE INDEX idx_notes_item_key ON notes(work_item_id, key);
CREATE INDEX idx_notes_item ON notes(work_item_id);
CREATE INDEX idx_notes_role ON notes(role);

-- Dependencies table (directed edges between work items)
CREATE TABLE dependencies (
    id              BLOB PRIMARY KEY DEFAULT (randomblob(16)),
    from_item_id    BLOB NOT NULL REFERENCES work_items(id) ON DELETE CASCADE,
    to_item_id      BLOB NOT NULL REFERENCES work_items(id) ON DELETE CASCADE,
    type            VARCHAR(20) NOT NULL DEFAULT 'BLOCKS'
                    CHECK (type IN ('BLOCKS', 'IS_BLOCKED_BY', 'RELATES_TO')),
    unblock_at      VARCHAR(20) CHECK (unblock_at IS NULL OR unblock_at IN ('queue', 'work', 'review', 'terminal')),
    created_at      TIMESTAMP NOT NULL
);

CREATE UNIQUE INDEX idx_deps_unique ON dependencies(from_item_id, to_item_id, type);
CREATE INDEX idx_deps_from ON dependencies(from_item_id);
CREATE INDEX idx_deps_to ON dependencies(to_item_id);

-- Role transitions table (audit trail)
CREATE TABLE role_transitions (
    id                  BLOB PRIMARY KEY DEFAULT (randomblob(16)),
    item_id             BLOB NOT NULL REFERENCES work_items(id) ON DELETE CASCADE,
    from_role           VARCHAR(20) NOT NULL,
    to_role             VARCHAR(20) NOT NULL,
    from_status_label   TEXT,
    to_status_label     TEXT,
    trigger             VARCHAR(50) NOT NULL,
    summary             TEXT,
    transitioned_at     TIMESTAMP NOT NULL
);

CREATE INDEX idx_role_trans_item ON role_transitions(item_id);
CREATE INDEX idx_role_trans_time ON role_transitions(transitioned_at);
