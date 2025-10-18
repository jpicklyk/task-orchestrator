# Subagent Stop Logger Hook

Logs metrics about subagent execution for analysis and optimization.

## Purpose

Tracks which agents are used, how often, and for which tasks/features. Enables:
- Understanding agent usage patterns
- Identifying most-used specialists
- Analyzing workflow efficiency
- Historical audit trail of agent invocations

## How It Works

1. **Listens** for `SubagentStop` events (any agent finishing)
2. **Extracts** agent name, task/feature IDs from subagent prompt
3. **Categorizes** agent by type (implementation, quality, documentation, coordination, planning)
4. **Logs** to both human-readable and CSV formats
5. **Reports** summary statistics back to orchestrator

## Configuration

Add to `.claude/settings.local.json`:

```json
{
  "hooks": {
    "SubagentStop": [{
      "hooks": [{
        "type": "command",
        "command": "\"$CLAUDE_PROJECT_DIR\"/.claude/hooks/subagent-stop-logger.sh"
      }]
    }]
  }
}
```

## Output Files

### Human-Readable Log
**Location**: `.claude/logs/subagent-metrics.log`

**Format**:
```
────────────────────────────────────────
Timestamp:       2025-10-18T15:30:42Z
Agent:           Backend Engineer
Category:        implementation
Session:         abc123...
Task ID:         78efafeb-2e07-47c4-8a79-21284198c482
Feature ID:      7f2882b5-3334-4c60-940e-4c8464f93023
Duration:        N/A
────────────────────────────────────────
```

### CSV Data
**Location**: `.claude/logs/subagent-metrics.csv`

**Columns**: `timestamp,agent_name,agent_category,session_id,task_id,feature_id,duration`

**Use for**: Spreadsheet analysis, charting, data science workflows

## Agent Categories

- **implementation**: Backend Engineer, Frontend Developer, Database Engineer
- **quality**: Test Engineer
- **documentation**: Technical Writer
- **coordination**: Feature Manager, Task Manager
- **planning**: Feature Architect, Planning Specialist
- **investigation**: Bug Triage Specialist

## Feedback to Orchestrator

After each subagent completes, the orchestrator sees:

```
✓ Logged subagent execution: Technical Writer (documentation)
  Session: abc123...
  Task: cd713e3b-feea-4450-8a1c-9f22a782f40a | Feature: 7f2882b5-3334-4c60-940e-4c8464f93023
  Total subagent runs: 12

Top 3 agents:
   5× Technical Writer
   4× Backend Engineer
   2× Test Engineer

Logs: /path/to/.claude/logs/subagent-metrics.log
Data: /path/to/.claude/logs/subagent-metrics.csv
```

## Analysis Examples

### Most Used Agents
```bash
tail -n +2 .claude/logs/subagent-metrics.csv | \
  cut -d',' -f2 | sort | uniq -c | sort -rn
```

### Tasks by Agent Category
```bash
tail -n +2 .claude/logs/subagent-metrics.csv | \
  cut -d',' -f3 | sort | uniq -c
```

### Agent Usage Over Time
```bash
tail -n +2 .claude/logs/subagent-metrics.csv | \
  cut -d',' -f1,2 | head -20
```

### Feature Agent Distribution
```bash
grep -v "^$" .claude/logs/subagent-metrics.csv | \
  awk -F',' '$6 != "" {print $6}' | sort | uniq -c | sort -rn
```

## Customization

### Add Duration Tracking

SubagentStop events don't include start time by default. To track duration, combine with SessionStart:

1. Create `.claude/hooks/session-start-tracker.sh` to log start times
2. Modify this hook to read start times and calculate duration
3. Store in shared JSON file (`.claude/logs/session-times.json`)

### Add Custom Categories

Edit lines 60-72 to add your own agent categories:

```bash
case "$AGENT_NAME" in
  *"Custom Agent"*) AGENT_CATEGORY="custom-category" ;;
  # ... existing cases
esac
```

### Export to External System

Add at end of script (before exit 0):

```bash
# Send to monitoring system
curl -X POST https://your-metrics-api.com/subagent \
  -H "Content-Type: application/json" \
  -d "{\"agent\":\"$AGENT_NAME\",\"timestamp\":\"$TIMESTAMP\"}"
```

### Filter by Agent Type

To only log specific agents:

```bash
# Add after line 28
if [[ ! "$AGENT_NAME" =~ (Backend|Frontend|Database) ]]; then
  exit 0  # Skip non-implementation agents
fi
```

## Integration with Analytics

### Load into Database

```sql
-- PostgreSQL example
CREATE TABLE subagent_metrics (
  timestamp TIMESTAMPTZ,
  agent_name VARCHAR(100),
  agent_category VARCHAR(50),
  session_id VARCHAR(100),
  task_id UUID,
  feature_id UUID,
  duration INTERVAL
);

\copy subagent_metrics FROM '.claude/logs/subagent-metrics.csv' CSV HEADER;
```

### Python Analysis

```python
import pandas as pd

df = pd.read_csv('.claude/logs/subagent-metrics.csv')
df['timestamp'] = pd.to_datetime(df['timestamp'])

# Most used agents
print(df['agent_name'].value_counts())

# Category distribution
print(df['agent_category'].value_counts())

# Timeline visualization
df.set_index('timestamp')['agent_name'].plot(kind='hist', bins=24)
```

## Troubleshooting

### No Task/Feature IDs Logged

**Problem**: Task/Feature ID columns always show "N/A"

**Solution**: Verify subagent prompts include IDs in expected format:
- Task: `"Work on task 78efafeb-2e07-47c4-8a79-21284198c482"`
- Feature: `"FEATURE: Name (7f2882b5-3334-4c60-940e-4c8464f93023)"`

### CSV File Corrupted

**Problem**: CSV contains malformed entries or extra commas

**Solution**:
```bash
# Backup current file
cp .claude/logs/subagent-metrics.csv .claude/logs/subagent-metrics.csv.bak

# Recreate with header only
echo "timestamp,agent_name,agent_category,session_id,task_id,feature_id,duration" \
  > .claude/logs/subagent-metrics.csv
```

### Logs Directory Not Created

**Problem**: `mkdir: cannot create directory`

**Solution**: Check permissions and ensure `$CLAUDE_PROJECT_DIR` is set:
```bash
echo $CLAUDE_PROJECT_DIR
ls -ld $CLAUDE_PROJECT_DIR/.claude
```

## Use Cases

### Optimize Agent Mix
Identify underutilized specialists to adjust workflow or agent definitions.

### Cost Analysis
Track token usage patterns across different agent types (if duration tracking added).

### Quality Metrics
Correlate Test Engineer invocations with bug count over time.

### Workflow Validation
Ensure proper agent sequencing (e.g., Planning → Implementation → Testing).

### Capacity Planning
Understand peak agent usage times and plan resource allocation.

## Security Considerations

- **No sensitive data**: Only logs agent names and IDs
- **Local storage**: All logs stored locally in `.claude/logs/`
- **No network calls**: Entirely offline operation
- **Safe failure**: Always exits 0 (non-blocking)
- **No user input**: All data from SubagentStop event

## Performance

- **Execution time**: < 50ms per invocation
- **Log file growth**: ~200 bytes per entry (~5KB per 25 agents)
- **CSV file growth**: ~120 bytes per entry (~3KB per 25 agents)
- **Disk impact**: Minimal (< 1MB for thousands of invocations)

### Log Rotation

For long-running projects, rotate logs monthly:

```bash
# Add to monthly cron or script
DATE=$(date +%Y-%m)
mv .claude/logs/subagent-metrics.log \
   .claude/logs/subagent-metrics-$DATE.log
mv .claude/logs/subagent-metrics.csv \
   .claude/logs/subagent-metrics-$DATE.csv
```
