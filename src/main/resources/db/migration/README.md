# Database Migrations Quick Reference

## Current Schema Version
- **V1**: Initial schema (all core application tables)
- **V2**: Migration test table (dummy table for testing)

## Next Migration: V3

### File Naming
```
V3__Add_Your_Feature_Table.sql
```

### Template
```sql
-- V3__Add_Your_Feature_Table.sql
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

1. **Never modify existing migration files** (V1__*, V2__*, etc.)
2. **Always use sequential version numbers** (V3, V4, V5...)
3. **Follow existing patterns**: BLOB IDs, timestamps, indexes
4. **Test thoroughly** before applying to production
5. **Document rollback steps** in migration comments

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

### Testing Tables (V2)
- `migration_test_table` - Dummy table for migration testing

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