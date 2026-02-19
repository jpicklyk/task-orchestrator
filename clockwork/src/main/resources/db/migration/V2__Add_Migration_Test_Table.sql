-- Add a test table for validating migration functionality
-- This table serves no business purpose except testing schema updates

CREATE TABLE migration_test_table (
    id BLOB PRIMARY KEY DEFAULT (randomblob(16)),
    test_name VARCHAR(255) NOT NULL,
    test_description TEXT,
    migration_version INTEGER NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    test_data TEXT
);

-- Create an index for testing index creation in migrations
CREATE INDEX idx_migration_test_name ON migration_test_table(test_name);
CREATE INDEX idx_migration_test_version ON migration_test_table(migration_version);

-- Insert a test record to verify the migration works
INSERT INTO migration_test_table (id, test_name, test_description, migration_version, created_at, test_data)
VALUES (
    randomblob(16),
    'Initial Migration Test',
    'This record validates that the migration_test_table was created successfully',
    2,
    CURRENT_TIMESTAMP,
    '{"test": true, "purpose": "schema_validation"}'
);