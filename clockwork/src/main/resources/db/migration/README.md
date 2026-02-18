# Database Migrations Quick Reference

## Current Schema Version (v2.0.0)

### Migration Files
- **V1**: Initial schema (v1.0.0) - All core application tables
- **V2**: Migration test table (v1.0.1) - Backward compatibility
- **V3**: Add optimistic locking versions (v1.0.1) - Version columns for concurrency
- **V4**: Add performance indexes (v1.0.1) - Query optimization indexes
- **V5**: Update simplified templates (v1.0.1) - Template content improvements
- **V6**: Upgrade to v2.0.0 schema - Description fields, status expansion, additional indexes

### Migration Paths

**Fresh Install (v2.0.0)**:
```
V1 → V2 → V3 → V4 → V5 → V6 (schema_version = 6)
```

**Upgrade from v1.0.1 to v2.0.0**:
```
Already has: V1, V2, V3, V4, V5
Applies: V6
(schema_version: 5 → 6)
```

### Production v1.0.1 Schema (V1-V5)
- **V1**: Core tables, indexes, triggers
- **V2**: Migration test table
- **V3**: Version columns (projects, features, sections)
- **V4**: Performance indexes (dependencies, search, composite)
- **V5**: Simplified template content (9 templates, 26 sections)

### V6 Adds (v2.0.0)
- **Description Fields**: Separate "what to do" (description) from "what was done" (summary)
  - Added to projects, features, tasks
- **Status Expansion**: v2.0 workflow support
  - Projects: 4 → 6 statuses (+ON_HOLD, +CANCELLED)
  - Features: 4 → 11 statuses (+DRAFT, +TESTING, +VALIDATING, +PENDING_REVIEW, +BLOCKED, +ON_HOLD, +DEPLOYED)
  - Tasks: 5 → 14 statuses (+BACKLOG, +IN_REVIEW, +CHANGES_REQUESTED, +TESTING, +READY_FOR_QA, +INVESTIGATING, +BLOCKED, +ON_HOLD, +DEPLOYED)
- **Additional Indexes**: Complex query optimization
  - idx_tasks_project_feature
  - idx_tasks_status_priority_complexity
  - idx_tasks_feature_status_priority
  - idx_features_status_priority
  - idx_features_project_status

## Next Migration: V7

### File Naming
```
V7__Add_Your_Feature_Table.sql
```

### Template
```sql
-- V7__Add_Your_Feature_Table.sql
-- Purpose: Brief description

CREATE TABLE your_table (
    id BLOB PRIMARY KEY DEFAULT (randomblob(16)),
    name VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    modified_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_your_table_name ON your_table(name);
```

### Testing
```bash
./gradlew test --tests "*migration*"
```

## ⚠️ Important Rules

1. **Never modify existing migration files** (V1__*, V2__*, V3__*, V4__*, V5__*, V6__*)
   - v1.0.1 production has V1-V5 applied
   - Changing them will cause checksum mismatches on upgrade
2. **Always use sequential version numbers** (V7, V8, V9...)
3. **Follow existing patterns**: BLOB IDs, timestamps, indexes
4. **Test thoroughly** before applying to production
5. **Document rollback steps** in migration comments
6. **V1-V5 from v1.0.1, V6 adds v2.0**: Next migration is V7

## 📚 Full Documentation

See [docs/developer-guides/database-migrations.md](../../../docs/developer-guides/database-migrations.md) for complete migration guide.

## Current Tables

### Core Application Tables (V1)
- `projects` - Top-level project organization
- `features` - Feature groupings within projects
- `tasks` - Primary work items
- `dependencies` - Task relationships
- `sections` - Rich content blocks
- `templates` - Documentation templates
- `template_sections` - Template content definitions
- `entity_tags` - Tag associations
- `entity_assignments` - Entity relationships
- `work_sessions` - Session tracking
- `task_locks` - Concurrency control

### Test Tables (V2)
- `migration_test_table` - Migration testing and backward compatibility

### V3 Enhancements (v1.0.1)
- Version columns for optimistic locking (projects, features, sections)
- Indexes for version checking

### V4 Enhancements (v1.0.1)
- Dependency lookup indexes
- Search vector indexes
- Composite indexes for common filter patterns

### V5 Enhancements (v1.0.1)
- Updated template content (9 templates)
- Simplified section structure (26 sections)

### V6 Enhancements (v2.0.0)
- Description fields (projects, features, tasks)
- Extended v2.0 status enums (31 total statuses)
- Additional performance indexes for complex queries

## SQLite Patterns Used

```sql
-- UUID Primary Keys
id BLOB PRIMARY KEY DEFAULT (randomblob(16))

-- Timestamps
created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
modified_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP

-- Foreign Keys
FOREIGN KEY (parent_id) REFERENCES parent_table(id)

-- Indexes
CREATE INDEX idx_table_column ON table_name(column_name);

-- Table Rebuild for Constraint Changes (SQLite limitation)
CREATE TABLE table_new (...);
INSERT INTO table_new SELECT ... FROM table_old;
DROP TABLE table_old;
ALTER TABLE table_new RENAME TO table_old;
```

## Version History

- **v1.0.0**: V1 (Initial schema)
- **v1.0.1**: V1, V2, V3, V4, V5 (Optimistic locking, indexes, templates)
- **v2.0.0**: V1, V2, V3, V4, V5, V6 (Description fields, status expansion, indexes)
