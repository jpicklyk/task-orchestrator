# Memory Configuration Examples

Ready-to-use memory configuration examples for Task Orchestrator workflows. Copy and adapt these for your team's needs.

## Available Examples

### [Minimal Configuration](memory-minimal.md)
**Use When**: Just getting started, small team, simple setup

**Features**:
- Basic PR preference only
- Default branch naming
- No custom workflows

**Best For**: Individual developers, small teams (2-3 people), learning the system

---

### [Startup Team](memory-startup.md)
**Use When**: Fast-moving environment, rapid iteration, minimal process

**Features**:
- No pull requests (direct to main)
- Simplified branch naming
- Fast deployment cycle

**Best For**: Early-stage startups, MVP development, small co-located teams (3-5 people)

---

### [Enterprise Team](memory-enterprise.md)
**Use When**: Compliance requirements, multiple approval gates, regulated industry

**Features**:
- Mandatory PRs with multiple approvals
- Security scanning required
- Multi-stage deployment (dev → staging → production)
- Comprehensive quality gates

**Best For**: Large organizations, regulated industries, teams with dedicated QA and security

---

### [Jira Integration](memory-jira.md)
**Use When**: Team uses Jira for project management, need ticket traceability

**Features**:
- Jira ticket references in branches
- Commit message prefixes with Jira IDs
- Automatic linking to Jira tickets

**Best For**: Teams using Atlassian suite, organizations requiring Jira tracking

---

### [Custom Bug Fix Workflow](memory-bugfix.md)
**Use When**: Production bugs need special handling, staging deployment mandatory

**Features**:
- Enhanced bug investigation process
- Mandatory staging deployment
- Root cause analysis required
- Post-deployment monitoring
- On-call team notifications

**Best For**: Production systems with SLAs, teams with on-call rotation, incident management process

---

## How to Use

1. **Choose an example** that matches your team's needs
2. **Copy the configuration** from the example file
3. **Customize** project keys, URLs, and specific commands
4. **Store** in appropriate location:
   - **Global**: AI's global memory (personal preferences)
   - **Project**: `CLAUDE.md` or `.cursorrules` in your repo (team preferences)
5. **Test** with a simple task first
6. **Iterate** based on team feedback

---

## Configuration Storage Locations

### For Claude Code
Store in project's `CLAUDE.md`:
```markdown
# Claude Code Project Memory

## Task Orchestrator - Implementation Workflow Configuration

[Paste configuration here]
```

### For Cursor
Store in project's `.cursorrules`:
```
# Task Orchestrator Workflow Configuration

[Paste configuration here]
```

### Global (Any AI)
Store in AI's global memory mechanism for user-wide preferences.

---

## Combining Examples

You can mix and match features from different examples:

**Example**: Jira Integration + Enterprise Quality Gates
```markdown
# From Jira example:
branch_naming_feature: "feature/PROJ-{task-id-short}-{description}"
commit_message_prefix: "[PROJ-{task-id-short}]"

# From Enterprise example:
use_pull_requests: "always"

## Feature Implementation Workflow Override
[Enterprise multi-stage deployment process]
```

---

## Progressive Enhancement

**Start Simple** → **Add Complexity** as your team grows:

1. **Day 1**: Minimal Configuration
   - Just PR preference

2. **Week 1**: Add Branch Naming
   - Custom patterns for your team

3. **Month 1**: Add Custom Workflows
   - Team-specific deployment steps

4. **Quarter 1**: Full Integration
   - Jira/incident management
   - Complete quality gates
   - Comprehensive monitoring

---

## Need Help?

- **[Workflow Prompts Documentation](../workflow-prompts.md#memory-based-workflow-customization)** - Complete customization guide
- **[AI Guidelines](../ai-guidelines.md#memory-based-workflow-customization-for-ai-agents)** - How AI uses memory
- **[Quick Start](../quick-start.md)** - Getting started with Task Orchestrator

---

## Contributing

Have a configuration pattern that works well for your team? Consider contributing it as an example! See the [Contributing Guide](../../CONTRIBUTING.md) for details.
