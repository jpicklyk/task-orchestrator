# Database Migrations Developer Guide

This guide covers how to create and manage database schema changes using Flyway migrations in the MCP Task Orchestrator project.

## Overview

The project uses [Flyway](https://flywaydb.org/) for versioned database schema management. Flyway automatically applies migrations in sequence and tracks which migrations have been applied.

**Configuration:**
- Enable with: `USE_FLYWAY=true` (default in Docker)
- Migration files: `src/main/resources/db/migration/`
- SQLite database with BLOB UUIDs and consistent patterns

## Creating New Migrations

### 1. File Naming Convention

```
src/main/resources/db/migration/V{number}__{Description}.sql
```

**Examples:**
- `V7__Add_User_Preferences_Table.sql`
- `V8__Add_Audit_Log_Indexes.sql`
- `V9__Migrate_Legacy_Status_Values.sql`

**Rules:**
- Use sequential numbers: V7, V8, V9... (V1-V6 already exist)
- Use double underscores `__` after version number
- Use descriptive names with underscores for spaces
- Never modify existing migration files after they're applied

### 2. Migration File Template

```sql
-- V7__{Description}.sql
--
-- Purpose: Brief description of what this migration does
--
-- ROLLBACK INSTRUCTIONS (if complex):
-- Manual steps needed to undo this migration:
-- 1. DROP TABLE new_table;
-- 2. Update flyway_schema_history to remove this version

-- Create new table following project patterns
CREATE TABLE example_table (
    id BLOB PRIMARY KEY DEFAULT (randomblob(16)),
    name VARCHAR(255) NOT NULL,
    description TEXT,
    status VARCHAR(50) NOT NULL DEFAULT 'active',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    modified_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    
    -- Add constraints
    UNIQUE(name),
    CHECK(status IN ('active', 'inactive', 'archived'))
);

-- Create indexes for performance
CREATE INDEX idx_example_table_status ON example_table(status);
CREATE INDEX idx_example_table_created_at ON example_table(created_at);

-- Insert test/default data (optional)
INSERT INTO example_table (id, name, description, status, created_at, modified_at)
VALUES (randomblob(16), 'Default Example', 'Default example record', 'active', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
```

## SQLite Best Practices

### Data Types
```sql
-- ✅ Correct: Use BLOB for UUIDs (matches existing schema)
id BLOB PRIMARY KEY DEFAULT (randomblob(16))

-- ✅ Correct: Consistent timestamp patterns
created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
modified_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP

-- ✅ Correct: Use VARCHAR with length limits
name VARCHAR(255) NOT NULL,
status VARCHAR(50) NOT NULL

-- ✅ Correct: Use TEXT for longer content
description TEXT,
content TEXT
```

### Constraints and Indexes
```sql
-- ✅ Add foreign key constraints
FOREIGN KEY (parent_id) REFERENCES parent_table(id),

-- ✅ Add unique constraints where needed
UNIQUE(user_id, setting_key),

-- ✅ Add check constraints for data validation
CHECK(status IN ('pending', 'in-progress', 'completed')),

-- ✅ Create indexes for common queries
CREATE INDEX idx_table_foreign_key ON table_name(foreign_key_column);
CREATE INDEX idx_table_status_priority ON table_name(status, priority);
```

## Testing Migrations

### Create Migration Tests
```kotlin
// src/test/kotlin/.../migration/V{N}MigrationTest.kt

@Test
fun `test V{N} migration creates expected tables and data`() {
    // Apply all migrations including new one
    assertTrue(schemaManager.updateSchema())
    
    // Verify schema version
    assertEquals({N}, schemaManager.getCurrentVersion())
    
    // Test new table exists
    transaction(database) {
        val tableExists = exec("SELECT name FROM sqlite_master WHERE type='table' AND name='new_table'") {
            it.next()
        } ?: false
        assertTrue(tableExists)
        
        // Test CRUD operations
        exec("INSERT INTO new_table (id, name) VALUES (randomblob(16), 'test')")
        
        val count = exec("SELECT COUNT(*) as count FROM new_table") { rs ->
            rs.next()
            rs.getInt("count")
        } as Int
        assertTrue(count > 0)
    }
}
```

### Run Migration Tests
```bash
# Test all migrations
./gradlew test --tests "*migration*"

# Test specific migration
./gradlew test --tests "*V{N}Migration*"

# Verify with application
USE_FLYWAY=true ./gradlew run
```

## Common Migration Patterns

### Adding New Tables
```sql
-- V{N}__Add_{Entity}_Table.sql
CREATE TABLE entities (
    id BLOB PRIMARY KEY DEFAULT (randomblob(16)),
    name VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    modified_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_entities_name ON entities(name);
```

### Adding Columns
```sql
-- V{N}__Add_{Column}_To_{Table}.sql
ALTER TABLE existing_table ADD COLUMN new_column VARCHAR(255);
CREATE INDEX idx_existing_table_new_column ON existing_table(new_column);
```

### Data Migrations
```sql
-- V{N}__Migrate_{Description}.sql
-- Update existing data to new format
UPDATE tasks SET new_status = 
    CASE old_status 
        WHEN 'todo' THEN 'pending'
        WHEN 'doing' THEN 'in-progress' 
        WHEN 'done' THEN 'completed'
        ELSE 'pending' 
    END
WHERE new_status IS NULL;
```

### Adding Indexes
```sql
-- V{N}__Add_Performance_Indexes.sql
CREATE INDEX idx_tasks_status_priority ON tasks(status, priority);
CREATE INDEX idx_features_project_created ON features(project_id, created_at);
```

## Real-World Migration Examples

### V4: Performance Optimization for Concurrent Access

**Purpose**: Improve query performance for multi-agent concurrent access by adding strategic indexes.

**File**: `src/main/resources/db/migration/V4__Add_Performance_Indexes.sql`

```sql
-- V4: Add performance-optimized indexes for common query patterns
--
-- Purpose: Optimize database performance for concurrent multi-agent access
-- This migration adds strategic indexes without changing schema structure
--
-- Performance Impact:
-- - Dependency lookups: 5-10x faster
-- - Search operations: 2-5x faster
-- - Filtered queries: 2-4x faster
--
-- ROLLBACK INSTRUCTIONS:
-- DROP INDEX IF EXISTS idx_dependencies_from_task_id;
-- DROP INDEX IF EXISTS idx_dependencies_to_task_id;
-- DROP INDEX IF EXISTS idx_tasks_search_vector;
-- DROP INDEX IF EXISTS idx_features_search_vector;
-- DROP INDEX IF EXISTS idx_projects_search_vector;
-- DROP INDEX IF EXISTS idx_tasks_status_priority;
-- DROP INDEX IF EXISTS idx_tasks_feature_status;
-- DROP INDEX IF EXISTS idx_tasks_project_status;
-- DROP INDEX IF EXISTS idx_tasks_priority_created;
-- DROP INDEX IF EXISTS idx_task_dependencies_type;

-- Dependency directional lookups (BLOCKS, IS_BLOCKED_BY queries)
CREATE INDEX IF NOT EXISTS idx_dependencies_from_task_id ON dependencies(from_task_id);
CREATE INDEX IF NOT EXISTS idx_dependencies_to_task_id ON dependencies(to_task_id);

-- Search vector indexes for full-text search optimization
CREATE INDEX IF NOT EXISTS idx_tasks_search_vector ON tasks(search_vector);
CREATE INDEX IF NOT EXISTS idx_features_search_vector ON features(search_vector);
CREATE INDEX IF NOT EXISTS idx_projects_search_vector ON projects(search_vector);

-- Composite indexes for common filter patterns
CREATE INDEX IF NOT EXISTS idx_tasks_status_priority ON tasks(status, priority);
CREATE INDEX IF NOT EXISTS idx_tasks_feature_status ON tasks(feature_id, status);
CREATE INDEX IF NOT EXISTS idx_tasks_project_status ON tasks(project_id, status);

-- Priority-based ordering with creation time
-- Note: SQLite doesn't support DESC in index definition, handled at query level
CREATE INDEX IF NOT EXISTS idx_tasks_priority_created ON tasks(priority, created_at);

-- Dependency type filtering
CREATE INDEX IF NOT EXISTS idx_task_dependencies_type ON dependencies(type);
```

**Testing**: All 817 tests passed after applying this migration, verifying backward compatibility.

**Schema Updates Required**: Updated corresponding table definitions in:
- `DependenciesTable.kt` - Added fromTaskId, toTaskId indexes
- `TaskTable.kt` - Added searchVector and 4 composite indexes
- `FeaturesTable.kt` - Added searchVector index
- `ProjectsTable.kt` - Added searchVector index

**Key Learnings**:
- Index-only migrations are low-risk and high-impact
- Composite indexes should match WHERE clause column order
- Always include IF NOT EXISTS for safe re-application
- Document performance improvements in migration comments

## Migration Workflow

### 1. Plan the Migration
- Identify what needs to change
- Consider impact on existing data
- Plan rollback strategy
- Check for dependencies on other systems

### 2. Create Migration File
- Use next sequential version number
- Follow naming convention
- Include rollback instructions in comments
- Follow project patterns and conventions

### 3. Test Thoroughly
- Create unit tests for the migration
- Test with existing data if possible
- Verify indexes and constraints work
- Test rollback procedure

### 4. Review and Deploy
- Code review the migration
- Test in staging environment
- Apply to production
- Monitor for issues

## Rollback Strategy

Flyway Community Edition doesn't support automatic rollbacks. For complex migrations:

### Document Rollback Steps
```sql
-- V7__Add_Complex_Feature.sql
--
-- ROLLBACK INSTRUCTIONS:
-- This migration adds audit_log table and triggers.
-- To manually rollback:
-- 1. DROP TRIGGER IF EXISTS audit_trigger;
-- 2. DROP TABLE IF EXISTS audit_log;
-- 3. DELETE FROM flyway_schema_history WHERE version = '7';
-- 4. Restart application to ensure clean state
```

### Manual Rollback Process
```bash
# 1. Stop the application (if running in background)
docker stop mcp-task-orchestrator 2>/dev/null || true

# 2. Connect to database and run rollback SQL
sqlite3 data/tasks.db < rollback_v7.sql

# 3. Update Flyway history
sqlite3 data/tasks.db "DELETE FROM flyway_schema_history WHERE version = '7';"

# 4. Restart application (production)
docker run --rm -i -v mcp-task-data:/app/data ghcr.io/jpicklyk/task-orchestrator:latest

# Or restart local development
docker run --rm -i -v mcp-task-data:/app/data mcp-task-orchestrator:dev
```

## Troubleshooting

### Common Issues

**Migration fails with "Detected resolved migration not applied to database"**
```bash
# Fix: Repair the schema history
# This usually happens when migration files are modified after being applied
```

**Foreign key constraint errors**
```sql
-- Ensure PRAGMA foreign_keys = ON is set (already configured)
-- Check that referenced tables exist and have correct column types
```

**Performance issues after migration**
```sql
-- Add missing indexes
CREATE INDEX idx_table_column ON table_name(frequently_queried_column);

-- Analyze query plans
EXPLAIN QUERY PLAN SELECT * FROM table_name WHERE condition;
```

### Debugging Migrations

```bash
# Enable debug logging (production)
docker run --rm -i -v mcp-task-data:/app/data -e LOG_LEVEL=debug -e USE_FLYWAY=true ghcr.io/jpicklyk/task-orchestrator:latest

# Or with local development
docker run --rm -i -v mcp-task-data:/app/data -e LOG_LEVEL=debug -e USE_FLYWAY=true mcp-task-orchestrator:dev

# Check Flyway schema history
sqlite3 data/tasks.db "SELECT * FROM flyway_schema_history ORDER BY installed_rank;"

# Verify table structure
sqlite3 data/tasks.db ".schema table_name"
```

## References

- [Flyway Documentation](https://flywaydb.org/documentation/)
- [SQLite Documentation](https://sqlite.org/docs.html)
- [Project Database Schema](../src/main/resources/db/migration/)
- [Migration Test Framework](../src/test/kotlin/io/github/jpicklyk/mcptask/infrastructure/database/migration/)

## See Also

- [API Reference](../api-reference.md) - MCP tools documentation
- [Troubleshooting](../troubleshooting.md) - General troubleshooting guide