---
layout: default
title: setup_project Tool
---

# setup_project - Initialize Task Orchestrator Project

**Permission**: ✏️ WRITE (creates files)

**Category**: System Tools

**Purpose**: Initialize core Task Orchestrator project configuration by creating `.taskorchestrator/` directory with configuration files.

## Overview

The `setup_project` tool initializes Task Orchestrator in your project by creating the `.taskorchestrator/` directory and copying default configuration files. This is the **first step** for any project using Task Orchestrator.

**What This Creates**:
1. `.taskorchestrator/` - Core configuration directory
2. `.taskorchestrator/config.yaml` - Orchestrator configuration (status progression, validation rules)
3. `.taskorchestrator/status-workflow-config.yaml` - Workflow definitions and event handlers
4. `.taskorchestrator/agent-mapping.yaml` - Agent routing configuration
5. `.taskorchestrator/orchestration/` - Workflow automation files

**Key Features**:
- **Idempotent**: Safe to run multiple times (skips existing files)
- **Non-destructive**: Won't overwrite your customizations
- **Version-aware**: Detects outdated configurations
- **AI-agnostic**: Works with any MCP client (Claude Desktop, Claude Code, Cursor, etc.)

**Next Step**: For Claude Code integration (Skills, Subagents), install the Task Orchestrator plugin via Claude Code plugin marketplace after this tool.

## Quick Start

### Basic Usage

Initialize Task Orchestrator in your project:

```json
{}
```

No parameters needed! Just call the tool.

### Response Example

#### First-Time Setup

```json
{
  "success": true,
  "message": "Task Orchestrator project setup completed successfully. Created config.yaml. Created status-workflow-config.yaml. Created agent-mapping.yaml. Created 5 orchestration file(s). Core configuration is ready. For Claude Code integration, install the Task Orchestrator plugin.",
  "data": {
    "directoryCreated": true,
    "configCreated": true,
    "workflowConfigCreated": true,
    "agentMappingCreated": true,
    "orchestrationFilesCreated": 5,
    "orchestrationFilesOutdated": 0,
    "directory": "/path/to/project/.taskorchestrator",
    "configPath": "/path/to/project/.taskorchestrator/config.yaml",
    "workflowConfigPath": "/path/to/project/.taskorchestrator/status-workflow-config.yaml",
    "agentMappingPath": "/path/to/project/.taskorchestrator/agent-mapping.yaml",
    "orchestrationPath": "/path/to/project/.taskorchestrator/orchestration",
    "hasOutdatedConfigs": false,
    "currentVersion": "2.0.0"
  }
}
```

#### Re-Run (Already Set Up)

```json
{
  "success": true,
  "message": "Task Orchestrator project setup verified. Config.yaml already exists. Workflow config already exists. Agent-mapping.yaml already exists. 5 orchestration file(s) already present. All configuration files already present.",
  "data": {
    "directoryCreated": false,
    "configCreated": false,
    "workflowConfigCreated": false,
    "agentMappingCreated": false,
    "orchestrationFilesCreated": 0,
    "orchestrationFilesOutdated": 0,
    "directory": "/path/to/project/.taskorchestrator",
    "hasOutdatedConfigs": false,
    "currentVersion": "2.0.0"
  }
}
```

#### With Outdated Configs

```json
{
  "success": true,
  "message": "Task Orchestrator project setup verified. Config.yaml already exists. All configuration files already present.\n\n⚠️  Configuration updates available:\n  - config.yaml: v1.1.0 → v2.0.0\n\nTo upgrade configurations while preserving customizations, run: update_project_config workflow",
  "data": {
    "directoryCreated": false,
    "configCreated": false,
    "workflowConfigCreated": false,
    "agentMappingCreated": false,
    "hasOutdatedConfigs": true,
    "currentVersion": "2.0.0",
    "outdatedConfigs": [
      {
        "filename": "config.yaml",
        "currentVersion": "1.1.0",
        "latestVersion": "2.0.0"
      }
    ]
  }
}
```

## Parameters

**No parameters required**. This tool takes no input.

## Response Schema

### Success Response

```json
{
  "success": boolean,
  "message": "Human-readable message with setup results",
  "data": {
    "directoryCreated": boolean,
    "configCreated": boolean,
    "workflowConfigCreated": boolean,
    "agentMappingCreated": boolean,
    "orchestrationFilesCreated": number,
    "orchestrationFilesOutdated": number,
    "directory": "string path",
    "configPath": "string path",
    "workflowConfigPath": "string path",
    "agentMappingPath": "string path",
    "orchestrationPath": "string path",
    "hasOutdatedConfigs": boolean,
    "currentVersion": "string version",
    "outdatedConfigs": [/* array if hasOutdatedConfigs */],
    "outdatedOrchestrationFiles": [/* array if any outdated */]
  }
}
```

### Response Fields

| Field | Type | Description |
|-------|------|-------------|
| `directoryCreated` | boolean | Whether `.taskorchestrator/` directory was newly created |
| `configCreated` | boolean | Whether `config.yaml` was newly created |
| `workflowConfigCreated` | boolean | Whether `status-workflow-config.yaml` was newly created |
| `agentMappingCreated` | boolean | Whether `agent-mapping.yaml` was newly created |
| `orchestrationFilesCreated` | number | Count of newly created orchestration files |
| `orchestrationFilesOutdated` | number | Count of orchestration files with updates available |
| `directory` | string | Absolute path to `.taskorchestrator/` directory |
| `configPath` | string | Absolute path to `config.yaml` |
| `workflowConfigPath` | string | Absolute path to `status-workflow-config.yaml` |
| `agentMappingPath` | string | Absolute path to `agent-mapping.yaml` |
| `orchestrationPath` | string | Absolute path to `orchestration/` directory |
| `hasOutdatedConfigs` | boolean | Whether any configuration files have updates available |
| `currentVersion` | string | Current version of configuration schema |
| `outdatedConfigs` | array | List of outdated config files (if `hasOutdatedConfigs: true`) |
| `outdatedOrchestrationFiles` | array | List of outdated orchestration files (if any) |

## Created Files

### 1. config.yaml

**Location**: `.taskorchestrator/config.yaml`

**Purpose**: Core orchestrator configuration

**Contents**:
- Status progression flows (default_flow, bug_fix_flow, documentation_flow, etc.)
- Flow mappings (tag-based flow selection)
- Status validation rules (enforce_sequential, allow_backward, validate_prerequisites)
- Quality gates configuration
- Parallelism settings

**Example**:
```yaml
status_validation:
  enforce_sequential: true
  allow_backward: true
  validate_prerequisites: true

status_progression:
  tasks:
    default_flow: [backlog, pending, in-progress, testing, completed]
    bug_fix_flow: [pending, in-progress, testing, completed]

    flow_mappings:
      - tags: [bug, bugfix]
        flow: bug_fix_flow
```

**Customization**: Edit this file to customize status workflows for your team.

### 2. status-workflow-config.yaml

**Location**: `.taskorchestrator/status-workflow-config.yaml`

**Purpose**: Detailed workflow definitions and event handlers

**Contents**:
- Complete flow definitions with status descriptions
- Event handler configuration
- Cascade event rules
- Terminal status definitions
- Emergency transition definitions

**Customization**: Add custom flows or modify event handling behavior.

### 3. agent-mapping.yaml

**Location**: `.taskorchestrator/agent-mapping.yaml`

**Purpose**: Map task tags to appropriate agents/specialists

**Contents**:
- Tag patterns for routing tasks
- Specialist recommendations by domain
- Section tag filtering for efficient context loading

**Example**:
```yaml
mappings:
  - tags: [backend, api, service]
    agent: Backend Engineer
    sectionTags: [technical-approach, api-design, implementation]

  - tags: [database, migration, schema]
    agent: Database Engineer
    sectionTags: [database-schema, migration-plan]
```

**Used By**: `recommend_agent` tool (Claude Code only)

### 4. orchestration/ Directory

**Location**: `.taskorchestrator/orchestration/`

**Purpose**: Workflow automation files (v2.0 progressive disclosure)

**Created Files**:
- `decision-trees.md` - Routing decisions for Skills vs Subagents
- `workflows.md` - Detailed workflow patterns and execution steps
- `examples.md` - Complete workflow examples
- `optimizations.md` - Token efficiency patterns
- `error-handling.md` - Error scenarios and resolutions

**Usage**: Referenced by Task Orchestrator output style for progressive disclosure (loads on-demand).

## Use Cases

### Use Case 1: First-Time Project Setup

**Scenario**: Starting a new project with Task Orchestrator.

**Steps**:
1. Run `setup_project` to create `.taskorchestrator/` directory
2. (Optional) Install Task Orchestrator plugin for Claude Code integration
3. Customize `.taskorchestrator/config.yaml` for your team's workflow
4. Commit `.taskorchestrator/` to git for team sharing

```bash
# In your project directory
setup_project  # Creates .taskorchestrator/

# Customize config (optional)
vim .taskorchestrator/config.yaml

# Commit for team
git add .taskorchestrator/
git commit -m "feat: add Task Orchestrator configuration"
```

### Use Case 2: Cloned Repository

**Scenario**: Teammate clones repository, needs Task Orchestrator setup.

**Problem**: Repository may not have `.taskorchestrator/` committed (in `.gitignore`).

**Solution**:
```bash
# Clone repo
git clone <repo-url>
cd <project>

# Initialize Task Orchestrator
setup_project  # Creates .taskorchestrator/ with defaults

# System is ready to use
```

### Use Case 3: Restore Default Configurations

**Scenario**: You modified config files and want to restore defaults.

**Options**:

**Option 1**: Delete and recreate (loses customizations)
```bash
rm -rf .taskorchestrator/
setup_project  # Creates fresh configs
```

**Option 2**: Selective restore (preserves some customizations)
```bash
# Backup current config
mv .taskorchestrator/config.yaml .taskorchestrator/config.yaml.backup

# Restore default
setup_project  # Creates default config.yaml

# Manually merge customizations
diff .taskorchestrator/config.yaml.backup .taskorchestrator/config.yaml
```

### Use Case 4: Version Upgrade

**Scenario**: Task Orchestrator has new features, need updated configs.

**Detection**:
```bash
setup_project  # Detects outdated configs

# Response includes:
# "⚠️  Configuration updates available:
#   - config.yaml: v1.1.0 → v2.0.0"
```

**Upgrade Path**:
1. Backup current configs
2. Review changelog for breaking changes
3. Manually merge new features or delete and recreate

```bash
# Backup
cp -r .taskorchestrator/ .taskorchestrator.backup/

# Review what changed
# (Compare default config from new version with your customizations)

# Option A: Manual merge (preserves customizations)
# Edit config files to add new features

# Option B: Fresh start (easier but loses customizations)
rm -rf .taskorchestrator/
setup_project
```

## Configuration Customization

After running `setup_project`, customize configurations for your team:

### 1. Customize Status Flows

Edit `.taskorchestrator/config.yaml`:

```yaml
status_progression:
  tasks:
    # Add custom flow for research tasks
    research_flow: [backlog, researching, documenting, peer-review, completed]

    flow_mappings:
      # Map research tasks to research_flow
      - tags: [research, investigation, analysis]
        flow: research_flow
```

### 2. Adjust Validation Rules

Edit `.taskorchestrator/config.yaml`:

```yaml
status_validation:
  enforce_sequential: false    # Allow skipping statuses
  allow_backward: true          # Allow rework
  validate_prerequisites: true  # Keep quality gates
```

### 3. Add Custom Agent Mappings

Edit `.taskorchestrator/agent-mapping.yaml`:

```yaml
mappings:
  # Add custom specialist for data science tasks
  - tags: [data-science, ml, ai]
    agent: Data Science Specialist
    sectionTags: [model-design, training-plan, evaluation]
```

### 4. Commit for Team Sharing

```bash
git add .taskorchestrator/
git commit -m "chore: customize Task Orchestrator workflows for team"
git push
```

**Team members** will now get your customized workflows when they clone and run `setup_project`.

## Integration with Task Orchestrator Plugin

`setup_project` creates core configuration, while the Task Orchestrator plugin provides Claude Code integration:

| Component | Purpose | Creates | Required For |
|-----------|---------|---------|--------------|
| `setup_project` | Core orchestrator config | `.taskorchestrator/` | All MCP clients |
| Task Orchestrator Plugin | Claude Code integration | `.claude/agents/`, `.claude/skills/` | Claude Code only |

**Recommended Setup Order**:
1. **First**: Run `setup_project` (creates core config)
2. **Then**: Install plugin via `/plugin install task-orchestrator` (creates Claude Code integration)

**Why This Order**:
- Plugin may reference config files created by `setup_project`
- Core config is needed for workflow automation regardless of AI client

## Best Practices

### 1. Run on Every New Project

```bash
# Starting a new project
mkdir my-project && cd my-project
git init

# Initialize Task Orchestrator
setup_project

# Commit configuration
git add .taskorchestrator/
git commit -m "feat: initialize Task Orchestrator"
```

### 2. Commit Configuration to Git

**Do** commit `.taskorchestrator/` for team collaboration:
```gitignore
# .gitignore - DO commit these
# .taskorchestrator/  # Don't ignore this!
```

**Don't** commit local overrides (if you create any):
```gitignore
# .gitignore
.taskorchestrator/config.local.yaml  # Local overrides
```

### 3. Document Customizations

Add a README explaining your workflow customizations:

```bash
# .taskorchestrator/README.md
echo "# Task Orchestrator Configuration

Our team customizations:
- Research tasks use research_flow (skip backlog)
- Bug fixes skip backlog (bug_fix_flow)
- Documentation tasks skip testing (documentation_flow)

Edit config.yaml to modify status progressions." > .taskorchestrator/README.md

git add .taskorchestrator/README.md
git commit -m "docs: document Task Orchestrator customizations"
```

### 4. Check for Updates Periodically

```bash
# Check if configs are outdated
setup_project

# If outdated, review changelog and upgrade
# https://github.com/jpicklyk/task-orchestrator/blob/main/CHANGELOG.md
```

## Error Handling

### Permission Errors

```json
{
  "success": false,
  "message": "Failed to setup Task Orchestrator project",
  "error": "Permission denied: Cannot create .taskorchestrator/"
}
```

**Fix**: Ensure you have write permissions in the current directory.

```bash
# Check permissions
ls -la

# Fix permissions (if needed)
chmod u+w .
```

### Disk Space Errors

```json
{
  "success": false,
  "message": "Failed to setup Task Orchestrator project",
  "error": "No space left on device"
}
```

**Fix**: Free up disk space.

## Related Tools

- **[Plugin Installation](../plugin-installation.md)** - Install Task Orchestrator plugin for Claude Code integration
- **Configuration Guide**: [status-progression.md](../status-progression.md) - Complete workflow configuration reference
- **Agent Mapping**: [agent-architecture.md](../agent-architecture.md) - Agent routing and specialist configuration

## Version History

- **v2.0.0** - Added orchestration directory with progressive disclosure files
- **v2.0.0** - Added version detection for outdated configurations
- **v2.0.0-beta** - Added status-workflow-config.yaml for event-driven workflows
- **v1.1.0** - Initial release with config.yaml and agent-mapping.yaml
