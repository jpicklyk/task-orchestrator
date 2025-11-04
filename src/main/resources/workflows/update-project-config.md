---
name: update-project-config
description: Upgrade Task Orchestrator configuration files (.taskorchestrator/) to latest versions with backup and customization preservation
version: "2.0.1"
---

# Update Project Configuration Workflow

This workflow upgrades your Task Orchestrator configuration files (.taskorchestrator/) to the latest versions while preserving your customizations.

## Step 1: Check Current Configuration Status

**Run setup_project tool** to detect current versions:

```
setup_project()
```

**Analyze the response**:
- Look for "hasOutdatedConfigs" field in response data
- If false: No updates needed, exit workflow
- If true: Continue to Step 2

**Response will show**:
```
⚠️  Configuration updates available:
  - config.yaml: v1.0.0 → v2.0.0
  - status-workflow-config.yaml: No version → v2.0.0
  - agent-mapping.yaml: v2.0.0 (up to date)
```

## Step 2: Backup Current Configuration

**REQUIRED**: Create backup before any modifications.

**Backup steps**:
1. Create backup directory with timestamp:
```bash
mkdir -p .taskorchestrator/backups
cp .taskorchestrator/config.yaml .taskorchestrator/backups/config.yaml.backup-YYYY-MM-DD-HHMMSS
cp .taskorchestrator/status-workflow-config.yaml .taskorchestrator/backups/status-workflow-config.yaml.backup-YYYY-MM-DD-HHMMSS
cp .taskorchestrator/agent-mapping.yaml .taskorchestrator/backups/agent-mapping.yaml.backup-YYYY-MM-DD-HHMMSS
```

2. Verify backups created successfully
3. Report backup location to user

## Step 3: Choose Upgrade Mode

**Present options to user** (use AskUserQuestion):

```
AskUserQuestion(
  questions: [{
    question: "How would you like to upgrade your Task Orchestrator configuration?",
    header: "Upgrade Mode",
    multiSelect: false,
    options: [
      {
        label: "Add New Files Only",
        description: "Safest - only copies missing files, preserves customizations"
      },
      {
        label: "Full Reset",
        description: "Destructive - overwrites ALL configs with defaults (requires backup)"
      },
      {
        label: "Cancel",
        description: "Keep existing configuration unchanged"
      }
    ]
  }]
)
```

## Step 4: Execute Upgrade (Based on Choice)

### Option "Add New Files Only"

**This is safe** - only creates missing files.

**Actions**:
1. For each outdated config file:
   - If file doesn't exist: Copy from defaults
   - If file exists: Skip (preserve user version)
2. Report which files were added vs skipped
3. User can manually merge updates if desired

**No tool calls needed** - setup_project already skips existing files.

**Report**:
```
✅ Upgrade complete (Add New Files mode)

Files added: (none, all files already present)
Files preserved: config.yaml, status-workflow-config.yaml, agent-mapping.yaml

Your customizations are intact. To adopt new features:
- Review latest defaults in source: src/main/resources/orchestration/
- Manually merge desired changes into your configs
- Backups available in: .taskorchestrator/backups/
```

### Option "Full Reset"

**⚠️  DESTRUCTIVE** - overwrites all config files.

**Confirmation required** (use AskUserQuestion):
```
AskUserQuestion(
  questions: [{
    question: "⚠️ WARNING: This will OVERWRITE all configuration files with defaults. All customizations will be LOST. Backups created in .taskorchestrator/backups/. Confirm destructive reset?",
    header: "Confirm Reset",
    multiSelect: false,
    options: [
      {
        label: "Confirm Reset",
        description: "Yes, overwrite all configs (backups preserved)"
      },
      {
        label: "Cancel",
        description: "Abort, keep existing configuration"
      }
    ]
  }]
)
```

**If "Confirm Reset"**:
1. Delete existing config files:
```bash
rm .taskorchestrator/config.yaml
rm .taskorchestrator/status-workflow-config.yaml
rm .taskorchestrator/agent-mapping.yaml
```

2. Run setup_project to recreate with defaults:
```
setup_project()
```

3. Verify new files created with latest versions

**Report**:
```
✅ Full reset complete

All configuration files reset to v2.0.0 defaults.
Backups preserved in: .taskorchestrator/backups/

If you need to restore customizations:
1. Review backups for your custom settings
2. Manually re-apply to new config files
3. Use config.yaml comments as guide for valid options
```

## Step 5: Verification

**Run setup_project again** to verify upgrade:

```
setup_project()
```

**Check response**:
- hasOutdatedConfigs should be false
- All config files should show latest version
- No warnings about updates

**If issues detected**:
- Restore from backups
- Report issue for troubleshooting

## Step 6: Update Team Documentation (If Committed)

**If .taskorchestrator/ is committed to git**:

1. Review changes:
```bash
git status
git diff .taskorchestrator/
```

2. Commit updates (if appropriate):
```bash
git add .taskorchestrator/
git commit -m "chore: upgrade Task Orchestrator config to v2.0.0"
```

3. Notify team of config updates

## Rollback Procedure (If Needed)

**If upgrade causes issues**, restore from backups:

```bash
# Find latest backup
ls -lt .taskorchestrator/backups/

# Restore from backup (replace TIMESTAMP)
cp .taskorchestrator/backups/config.yaml.backup-TIMESTAMP .taskorchestrator/config.yaml
cp .taskorchestrator/backups/status-workflow-config.yaml.backup-TIMESTAMP .taskorchestrator/status-workflow-config.yaml
cp .taskorchestrator/backups/agent-mapping.yaml.backup-TIMESTAMP .taskorchestrator/agent-mapping.yaml
```

## Best Practices

**When to upgrade**:
- After Task Orchestrator MCP server update
- When setup_project reports outdated configs
- When you need new configuration features

**Before upgrading**:
- Commit current state to git (if tracked)
- Create manual backup
- Review changelog for breaking changes

**After upgrading**:
- Test basic operations (create task, update status)
- Review new config options and customize
- Update team documentation if needed

This workflow ensures safe configuration upgrades with minimal risk to your customizations.
