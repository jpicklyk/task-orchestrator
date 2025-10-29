# Database Migrations Quick Reference

## Current Schema Version (v2.0.0)

### Migration Files
- **V1**: Initial schema (v1.0.1) - All core application tables
- **V2**: Migration test table (v1.0.1) - Backward compatibility
- **V3**: Upgrade to v2.0.0 schema - All v2.0 enhancements

### Migration Paths
**Fresh Install**: V1 → V2 → V3 (schema_version = 3)
**Upgrade from v1.0.1**: Already has V1+V2, applies V3 (2 → 3)

### V3 Includes
- Extended v2.0 status enums (31 total statuses)
- Optimistic locking (version columns on all entities)
- Description fields for projects and features
- Comprehensive performance indexes (73+ total)
- Built-in template initialization (9 templates, 26 sections)

## Next Migration: V4

### File Naming
```
V4__Add_Your_Feature_Table.sql
```

### Template
```sql
-- V4__Add_Your_Feature_Table.sql
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

1. **Never modify existing migration files** (V1__*, V2__*, V3__*, etc.)
2. **Always use sequential version numbers** (V4, V5, V6...)
3. **Follow existing patterns**: BLOB IDs, timestamps, indexes
4. **Test thoroughly** before applying to production
5. **Document rollback steps** in migration comments
6. **V1+V2 from v1.0.1, V3 adds v2.0**: Next migration is V4

## 📚 Full Documentation

See [docs/database-migrations.md](../../../docs/database-migrations.md) for complete migration guide.

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

### V3 Enhancements
- Extended status enums (31 values across all entities)
- Version columns (optimistic locking)
- Description fields (projects, features)
- Performance indexes (73+ total)
- Template initialization (9 templates, 26 sections)

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
```