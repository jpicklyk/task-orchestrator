---
description: Check the Flyway schema version of the SQLite database on a Docker volume
---

Check the schema version of the Task Orchestrator database on a Docker volume.

# Instructions

1. Parse the volume name from the user's command, defaulting to "mcp-task-data" if not provided
2. Create a temporary SQL file with the query
3. Run the Docker command mounting both the volume and the project directory
4. Display the results in a clear, formatted way
5. Clean up the temporary SQL file

# Working Pattern (Windows Git Bash Compatible)

Due to shell escaping issues on Windows, use a mounted SQL file approach:

**Step 1:** Create temporary SQL file in project root:
```sql
-- File: .tmp_check_schema.sql (in project root directory)
.mode box
.headers on

SELECT
    installed_rank,
    version,
    description,
    type,
    script,
    installed_on,
    execution_time,
    success
FROM flyway_schema_history
ORDER BY installed_rank DESC
LIMIT 10;
```

**Temp File Location:** Create `.tmp_check_schema.sql` in the project root directory (D:\Projects\task-orchestrator\)

**Step 2:** Run via Docker with mounted SQL file:
```bash
powershell.exe -Command "docker run --rm -v {{VOLUME_NAME}}:/data -v '${PWD}:/work' alpine sh -c 'apk add --no-cache sqlite >/dev/null 2>&1 && sqlite3 /data/tasks.db < /work/.tmp_check_schema.sql'"
```

**Step 3:** Clean up:
```bash
rm .tmp_check_schema.sql
```

**Note:** The `alpine/sqlite` image doesn't work well with inline SQL on Windows. Use `alpine` with sqlite installed instead.

# Expected Output Format

Present the results with:
- Current schema version (the most recent migration)
- List of recent migrations (up to 10)
- Success status for each migration
- Installation timestamps

If the query fails:
- Explain that the volume may not exist, or
- The database may not have been initialized with Flyway, or
- The database file doesn't exist yet

# Additional Helpful Commands

After showing the results, offer these follow-up options:

1. **Show all migrations:** Remove the LIMIT from the SQL query
2. **Show table structure:** Create SQL file with `.schema flyway_schema_history`
3. **Show all tables:** Create SQL file with `.tables`
4. **Check database file:**
   ```bash
   docker run --rm -v {{VOLUME_NAME}}:/data alpine ls -lh /data/
   ```

# Usage Examples

```
/check_schema_version
```
Checks the default `mcp-task-data` volume.

```
/check_schema_version my-custom-volume
```
Checks the `my-custom-volume` Docker volume.
