# Real World Setup Examples

This page contains community-contributed examples of how different teams and individuals have set up MCP Task Orchestrator for their specific needs.

## Individual Developer Setups

### Web Developer - Personal Projects

**Use Case**: Managing multiple personal web development projects with different tech stacks.

**Setup**:
```json
{
  "mcpServers": {
    "task-orchestrator": {
      "command": "docker",
      "args": [
        "run", "--rm", "-i",
        "--volume", "dev-tasks:/app/data",
        "--env", "MCP_DEBUG=false",
        "mcp-task-orchestrator"
      ]
    }
  }
}
```

**Project Structure**:
- **Project**: "Portfolio Website Redesign"
  - **Feature**: "Modern UI Components"
  - **Feature**: "Performance Optimization"
  - **Feature**: "SEO Improvements"

**Workflow**: Uses `create_feature_workflow` for each major feature, applies `technical-approach` and `testing-strategy` templates consistently.

**Tags Used**: `frontend`, `backend`, `design`, `performance`, `seo`

---

### Data Scientist - Research Projects

**Use Case**: Managing multiple research projects with data analysis pipelines.

**Setup**:
```json
{
  "mcpServers": {
    "task-orchestrator": {
      "command": "docker",
      "args": [
        "run", "--rm", "-i",
        "--volume", "/home/researcher/task-data:/app/data",
        "--env", "DATABASE_PATH=/app/data/research-tasks.db",
        "mcp-task-orchestrator"
      ]
    }
  }
}
```

**Custom Templates Created**:
- "Data Analysis Template" with sections for data sources, methodology, and validation
- "Research Paper Template" with literature review, methodology, and results sections

**Typical Commands**:
```
"Create a project for analyzing customer churn patterns"
"Apply the data analysis template to this feature"
"Use the sprint planning workflow for my quarterly research goals"
```

## Team Setups

### Startup Team (5 developers)

**Use Case**: Fast-moving startup with multiple product features in development.

**Shared Configuration**:
- All team members use same Docker volume name for consistency
- Standardized tagging system: `frontend`, `backend`, `mobile`, `infrastructure`
- Weekly sprint planning using `sprint_planning_workflow`

**Team Workflow**:
1. **Monday**: Product owner creates features using `create_feature_workflow`
2. **Tuesday**: Developers break down features using `task_breakdown_workflow`
3. **Daily**: Stand-ups use task status queries: `"Show me all in-progress tasks"`
4. **Friday**: Sprint review with completed task summaries

**Custom Tags**:
- `priority-p0` (critical), `priority-p1` (important), `priority-p2` (nice-to-have)
- `team-frontend`, `team-backend`, `team-mobile`
- `milestone-mvp`, `milestone-beta`, `milestone-launch`

---

### Enterprise Development Team (20+ developers)

**Use Case**: Large enterprise with multiple products and complex dependencies.

**Configuration Strategy**:
- Separate task orchestrator instances per product team
- Shared templates for company-wide consistency
- Integration with existing CI/CD through GitHub workflows

**Custom Templates**:
- "Security Review Template" - mandatory for all features
- "Compliance Check Template" - regulatory requirements
- "Performance Baseline Template" - performance standards

**Process Integration**:
- Features linked to JIRA epics through task summaries
- Automated task creation from GitHub issue templates
- Weekly cross-team dependency reviews using dependency tools

## Development Environment Variations

### macOS Development Setup

```json
{
  "mcpServers": {
    "task-orchestrator": {
      "command": "docker",
      "args": [
        "run", "--rm", "-i",
        "--volume", "mcp-task-data:/app/data",
        "--platform", "linux/amd64",
        "mcp-task-orchestrator"
      ]
    }
  }
}
```

**Note**: The `--platform` flag ensures compatibility on Apple Silicon Macs.

### Windows Development Setup

```json
{
  "mcpServers": {
    "task-orchestrator": {
      "command": "docker",
      "args": [
        "run", "--rm", "-i",
        "--volume", "mcp-task-data:/app/data",
        "--env", "MCP_DEBUG=false",
        "mcp-task-orchestrator"
      ]
    }
  }
}
```

**Windows-Specific Notes**:
- Use PowerShell or Command Prompt for Docker commands
- Config file location: `%APPDATA%\Claude\claude_desktop_config.json`
- Volume names work the same as other platforms

### Linux Server Setup

For headless Linux servers running the task orchestrator for team access:

```bash
# Docker compose setup for persistent server
version: '3.8'
services:
  task-orchestrator:
    image: mcp-task-orchestrator
    volumes:
      - task-data:/app/data
    environment:
      - MCP_TRANSPORT=stdio
      - MCP_DEBUG=false
    restart: unless-stopped

volumes:
  task-data:
```

## Integration Examples

### CI/CD Integration

**GitHub Actions Workflow**:
```yaml
name: Update Task Status
on:
  pull_request:
    types: [opened, closed]

jobs:
  update-tasks:
    runs-on: ubuntu-latest
    steps:
      - name: Update task status
        run: |
          # Parse PR title for task ID
          # Update task status via MCP tools
          # Add PR link to task sections
```

### Slack Integration

**Custom Bot Commands**:
- `/task-status` - Show personal in-progress tasks
- `/feature-progress [feature-name]` - Show feature completion status
- `/sprint-summary` - Show current sprint progress

## Troubleshooting Examples

### Common Setup Issues

**Issue**: "Docker container exits immediately"
**Solution**: Check Docker Desktop is running and image was built correctly
```bash
docker images | grep mcp-task-orchestrator
docker logs [container-id]
```

**Issue**: "Claude can't find task tools"
**Solution**: Restart Claude Desktop and verify config file syntax
```bash
# Validate JSON
cat ~/.config/Claude/claude_desktop_config.json | jq .
```

**Issue**: "Volume permission errors"
**Solution**: Recreate the Docker volume
```bash
docker volume rm mcp-task-data
docker volume create mcp-task-data
```

## Performance Optimization Examples

### Large Project Optimization

For projects with 1000+ tasks:
- Use pagination in search queries
- Apply summary views for overview operations
- Regular database maintenance
- Consider task archiving for completed sprints

### Memory-Constrained Environments

```json
{
  "command": "docker",
  "args": [
    "run", "--rm", "-i",
    "--memory", "512m",
    "--volume", "mcp-task-data:/app/data",
    "mcp-task-orchestrator"
  ]
}
```

## Contributing Your Setup

To add your setup example to this page:

1. Include your use case and team size
2. Provide the actual configuration (sanitized)
3. Describe your workflow and any custom templates
4. Share specific commands or integrations you use
5. Note any challenges and how you solved them

**Template for Contributions**:
```markdown
### [Your Use Case] - [Team Size/Type]

**Use Case**: Brief description

**Setup**: Configuration details

**Workflow**: How you use it day-to-day

**Custom Elements**: Templates, tags, integrations

**Tips**: What works well for your situation
```