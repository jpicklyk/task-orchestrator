---
layout: default
title: Quick Start
---

# Quick Start Guide

Get MCP Task Orchestrator running with your MCP-compatible AI agent in under 2 minutes. This guide shows configuration for Claude Desktop and Claude Code, but works with any AI that supports the Model Context Protocol.

## Prerequisites

- **Docker Desktop** installed and running
- **MCP-compatible AI agent** (Claude Desktop, Claude Code, Cursor, or other)
- **Basic CLI access** (Terminal/Command Prompt)

> **New to Docker?** [Download Docker Desktop](https://www.docker.com/products/docker-desktop/) - free for personal use.

---

## Step 1: Get the Docker Image

Pull the latest production image:

```bash
docker pull ghcr.io/jpicklyk/task-orchestrator:latest
```

> **Want to build from source or use a development version?** See the [Installation Guide](installation-guide) for detailed instructions.

---

## Step 2: Test the Installation

Verify the image works:

```bash
docker run --rm -i -v mcp-task-data:/app/data ghcr.io/jpicklyk/task-orchestrator:latest
```

You should see MCP server startup messages. Press `Ctrl+C` to stop.

---

## Step 3: Configure Your AI Agent

Choose your configuration method based on your AI agent:

### Option A: Claude Desktop

**Find Your Configuration File**:
- **Windows**: `%APPDATA%\Claude\claude_desktop_config.json`
- **macOS**: `~/Library/Application Support/Claude/claude_desktop_config.json`
- **Linux**: `~/.config/Claude/claude_desktop_config.json`

**Add Task Orchestrator**:

```json
{
  "mcpServers": {
    "task-orchestrator": {
      "command": "docker",
      "args": [
        "run",
        "--rm",
        "-i",
        "--volume",
        "mcp-task-data:/app/data",
        "ghcr.io/jpicklyk/task-orchestrator:latest"
      ]
    }
  }
}
```

> **Already have MCP servers configured?** Add the `task-orchestrator` entry to your existing `mcpServers` object.

**Restart Claude Desktop**: Close and reopen to load the configuration.

### Option B: Claude Code

Use the MCP configuration command:

```bash
claude mcp add-json task-orchestrator '{"type":"stdio","command":"docker","args":["run","--rm","-i","-v","mcp-task-data:/app/data","ghcr.io/jpicklyk/task-orchestrator:latest"]}'
```

Claude Code will automatically configure and connect to the MCP server.

### Option C: Cursor IDE

**Find Your Configuration File**:
- **Windows**: `%APPDATA%\Cursor\User\globalStorage\rooveterinaryinc.roo-cline\settings\cline_mcp_settings.json`
- **macOS**: `~/Library/Application Support/Cursor/User/globalStorage/rooveterinaryinc.roo-cline/settings/cline_mcp_settings.json`
- **Linux**: `~/.config/Cursor/User/globalStorage/rooveterinaryinc.roo-cline/settings/cline_mcp_settings.json`

**Add Task Orchestrator**:

```json
{
  "mcpServers": {
    "task-orchestrator": {
      "command": "docker",
      "args": [
        "run",
        "--rm",
        "-i",
        "--volume",
        "mcp-task-data:/app/data",
        "ghcr.io/jpicklyk/task-orchestrator:latest"
      ]
    }
  }
}
```

**Restart Cursor**: Close and reopen to load the configuration.

### Option D: Windsurf

**Find Your Configuration File**:
- **Windows**: `%APPDATA%\Windsurf\User\globalStorage\rooveterinaryinc.roo-cline\settings\cline_mcp_settings.json`
- **macOS**: `~/Library/Application Support/Windsurf/User/globalStorage/rooveterinaryinc.roo-cline/settings/cline_mcp_settings.json`
- **Linux**: `~/.config/Windsurf/User/globalStorage/rooveterinaryinc.roo-cline/settings/cline_mcp_settings.json`

**Add Task Orchestrator**:

```json
{
  "mcpServers": {
    "task-orchestrator": {
      "command": "docker",
      "args": [
        "run",
        "--rm",
        "-i",
        "--volume",
        "mcp-task-data:/app/data",
        "ghcr.io/jpicklyk/task-orchestrator:latest"
      ]
    }
  }
}
```

**Restart Windsurf**: Close and reopen to load the configuration.

> **Note**: Cursor and Windsurf use Cline (formerly Roo Cline) for MCP support. The configuration format is identical to Claude Desktop.

### Option E: Other MCP-Compatible AI Agents

For other MCP-supporting AI agents, consult their MCP configuration documentation. Use the Docker command configuration shown above, adapting to your agent's configuration format.

---

## Step 4: Verify Connection and Initialize

### Verify the Connection

Open your AI agent and ask:

```
Show me an overview of my current tasks
```

Your AI agent should respond confirming the task orchestrator is working (showing no tasks initially).

### Verify AI Guidelines Initialization

Your AI agent can access Task Orchestrator guidelines through MCP resources. The AI may read these automatically, or you can trigger explicit initialization.

**Check if initialized**: Look for a "Task Orchestrator - AI Initialization" section in your project's AI memory file:
- **Claude Code**: `CLAUDE.md`
- **Cursor**: `.cursorrules`
- **Windsurf**: `.windsurfrules`
- **GitHub Copilot**: `.github/copilot-instructions.md`

If the section doesn't exist or you want to ensure proper initialization, ask your AI agent:

```
Initialize Task Orchestrator using the initialize_task_orchestrator workflow
```

Or, if your AI agent supports direct prompt invocation:

```
/task-orchestrator:initialize_task_orchestrator
```

Your AI will:
1. Read all Task Orchestrator guideline resources
2. Write key patterns to your AI's memory file (CLAUDE.md, .cursorrules, etc.)
3. Confirm initialization with the specific file location

> **Why this matters**: Initialization enables your AI to autonomously discover templates, recognize patterns, and apply best practices without explicit instructions. The initialization persists across all sessions.

> **Note**: Initialization is typically **per-project** (stored in project root). If you work with Task Orchestrator across multiple projects, you may need to initialize once per project, or use global AI memory if your AI agent supports it.

---

## Understanding Your Setup Options

Before creating your first project, understand your options based on your situation:

### Option 1: New Project (Greenfield Development)

**When to use**: Building new software from scratch

**What you get**: Full project hierarchy (Project → Features → Tasks)

**Example scenarios**:
- "I'm building a new web application"
- "Starting a mobile app project"
- "Creating a new microservice"

### Option 2: Existing Codebase (Task Tracking)

**When to use**: Tracking work on existing software

**What you get**: Project container for organizing work items on existing code

**Example scenarios**:
- "I need to track bug fixes for my production app"
- "Managing enhancements to an existing system"
- "Organizing refactoring work"

### Option 3: Simple Task Tracking (No Project)

**When to use**: Small-scale work without project overhead

**What you get**: Just features and tasks (projects are optional!)

**Example scenarios**:
- "Quick prototype work"
- "Personal learning projects"
- "Ad-hoc task management"

> **Key Insight**: Projects are optional! You can create features and tasks directly without a top-level project container.

---

## Step 5: Create Your First Work Items

Choose the path that matches your situation from Step 4:

### Path A: New Project (Greenfield)

For building new software from scratch.

**Option 1: Quick Natural Language (Autonomous)**

Let your AI handle project setup automatically:

```
Create a new project called "My Web App" for building a web application
with user authentication and a dashboard
```

Your AI will autonomously:
- Create the project with appropriate structure
- Set up initial organization
- Suggest next steps for features and tasks

**Option 2: Comprehensive Guided Setup (Workflow Prompt)**

For step-by-step guidance with best practices, use the workflow prompt:

```
Set up a new project using the project_setup_workflow
```

Or with direct invocation:
```
/task-orchestrator:project_setup_workflow
```

Your AI will guide you through:
- Project creation with documentation
- Feature planning and structure
- Initial task creation
- Template strategy setup
- Development workflow establishment

**Then add features and tasks:**

```
Create a feature called "User Authentication" with appropriate templates
```

```
Create tasks for implementing user login, registration, and password reset
```

**View your structure:**

```
Show me an overview of my project
```

> **Which to use?** Use natural language for quick setup. Use the workflow prompt for comprehensive guidance and learning.

### Path B: Existing Codebase

For tracking work on existing software:

```
Create a project called "Production App Maintenance" to track work on my existing application
```

Then import existing work items:

```
Create tasks for the following work items:
- Fix authentication timeout bug
- Add user profile editing
- Implement API rate limiting
- Update documentation
```

Organize with features:

```
Create features to organize these tasks:
- User Management feature
- API Infrastructure feature
- Documentation feature
```

Link tasks to features:

```
Move the user-related tasks to the User Management feature
```

### Path C: Simple Task Tracking

For lightweight work without projects.

**Quick approach:**

```
Create a feature called "Learning React" with relevant templates
```

```
Create tasks for:
- Complete React tutorial
- Build sample app
- Deploy to production
```

**With guided setup (optional):**

```
Create a feature using the create_feature_workflow
```

Or: `/task-orchestrator:create_feature_workflow`

**View your work:**

```
Show me all pending tasks
```

> **Pro Tip**: You can always add a project container later if your work grows in scope. Start simple and scale up as needed.

---

## Understanding Workflow Prompts

Task Orchestrator provides **workflow prompts** for comprehensive, step-by-step guidance. You have two ways to work:

### Autonomous Mode (Natural Language)
Simply describe what you want in natural language. Your AI recognizes intent and applies appropriate patterns automatically.

**Example**: "Create a feature for user authentication" → AI autonomously creates feature with templates

**Best for**: Quick operations, experienced users, clear simple requests

### Workflow Prompt Mode (Explicit Guidance)
Invoke specific workflow prompts for comprehensive guidance with teaching and best practices.

**Example**: `/task-orchestrator:create_feature_workflow` → AI guides you step-by-step

**Best for**: Learning, complex scenarios, comprehensive setup, edge cases

**Available Workflow Prompts**:
- `initialize_task_orchestrator` - Set up AI guidelines
- `project_setup_workflow` - Comprehensive project initialization
- `create_feature_workflow` - Guided feature creation with tasks
- `task_breakdown_workflow` - Decompose complex tasks systematically
- `implementation_workflow` - Smart implementation with git detection

> **When to use which?** Use natural language for speed and efficiency. Use workflow prompts for learning, complex scenarios, or when you want detailed guidance.

---

## What You Can Do

### Project Organization
- Create hierarchical projects with features and tasks
- Apply templates for consistent documentation
- Track status, priority, and complexity

### Template-Driven Documentation
- 9 built-in templates for common scenarios
- Automatic template discovery by your AI agent
- Compose multiple templates for comprehensive coverage

### Workflow Automation
- 6 built-in workflow prompts for complex scenarios
- Autonomous pattern application
- Dependency management

### Natural Language Control
Simply ask your AI agent:
- "Create a task to implement the login API"
- "Show me all high-priority pending tasks"
- "Apply the technical approach template to this task"
- "What should I work on next?"

---

## PRD-Driven Development Workflow

**Product Requirements Documents (PRDs) provide the most effective workflow** for AI-assisted development. This approach gives your AI agent comprehensive context for intelligent breakdown and planning.

### Why PRD-Driven Development Works Best

- **Complete Context**: AI analyzes entire requirements at once
- **Intelligent Breakdown**: Automatic decomposition into features and tasks
- **Consistent Documentation**: Templates applied systematically
- **Proper Sequencing**: Dependencies created based on technical requirements
- **Better Results**: AI makes more informed decisions with full context

### Step-by-Step PRD Workflow

**Step 1: Provide Your PRD**

Share your PRD file with your AI agent:
```
I have a PRD for a new authentication system. Let me share it with you:

[Paste PRD content or share file path]

Please analyze this PRD and break it down into a project structure with features and tasks.
```

**Step 2: AI Analyzes and Plans**

Your AI agent will:
1. Read and analyze the entire PRD
2. Identify major features and functional areas
3. Create a project structure
4. Break down features into specific tasks
5. Apply appropriate templates for documentation
6. Create dependencies based on technical requirements

**Step 3: Review and Refine**

Your AI agent presents the breakdown:
```
AI Agent: "I've analyzed your authentication PRD. Here's the proposed structure:

Project: Authentication System v2

Features:
1. OAuth Integration (5 tasks)
   - Complexity: High
   - Templates: Technical Approach, Requirements Specification

2. Session Management (4 tasks)
   - Complexity: Medium
   - Templates: Technical Approach, Testing Strategy

3. User Profile API (3 tasks)
   - Complexity: Low
   - Templates: Task Implementation Workflow

Dependencies identified:
- OAuth Integration BLOCKS Session Management
- Session Management BLOCKS User Profile API

Should I proceed with creating this structure?"
```

**Step 4: Create Structure**

Approve and your AI agent creates:
```
Yes, please create this structure with all templates and dependencies.
```

**Step 5: Start Implementation**

Your AI agent creates everything and you're ready to work:
```
Show me what tasks I should start with
```

### Example PRD Workflow

**User provides PRD**:
```
Analyze this PRD and create a complete project structure:

# E-commerce Checkout System PRD

## Overview
Build a modern checkout system with payment processing, inventory validation,
and order confirmation.

## Requirements
- Real-time inventory checking
- Multiple payment methods (credit card, PayPal, Apple Pay)
- Email confirmations
- Order tracking
- Admin order management

## Technical Constraints
- Must integrate with existing inventory API
- Payment processing via Stripe
- Email via SendGrid
- Support 1000 concurrent users
```

**AI Agent's Response**:
```
I'll analyze this PRD and create a comprehensive project structure.

[AI agent uses get_overview, creates project, creates features with templates,
creates tasks with dependencies, applies templates]

Created project "E-commerce Checkout System" with:

Features:
1. Inventory Integration (3 tasks) - BLOCKS other features
2. Payment Processing (5 tasks) - Core functionality
3. Order Management (4 tasks) - Depends on Payment
4. Email Notifications (2 tasks) - Can run in parallel
5. Admin Dashboard (3 tasks) - Final integration

Total: 5 features, 17 tasks
Templates applied: Technical Approach, Requirements Specification, Testing Strategy
Dependencies: 8 blocking relationships created

Ready to start implementation. Recommended sequence:
1. Begin with Inventory Integration (unblocks other work)
2. Parallel: Email Notifications setup
3. Payment Processing (depends on inventory)
4. Order Management (depends on payment)
5. Admin Dashboard (final integration)
```

### PRD Best Practices

**What Makes a Good PRD for AI Breakdown**:
- Clear functional requirements
- Technical constraints specified
- User flows described
- Integration points identified
- Success criteria defined

**PRD Format Suggestions**:
- **Structured Markdown**: Headers, lists, clear sections
- **Technical Details**: APIs, libraries, constraints
- **User Stories**: "As a user, I want to..."
- **Acceptance Criteria**: Clear definition of done
- **Non-Functional Requirements**: Performance, security, scalability

**Example PRD Structure**:
```markdown
# Feature Name

## Problem Statement
What problem does this solve?

## Requirements
### Functional
- Feature 1
- Feature 2

### Non-Functional
- Performance targets
- Security requirements

## Technical Approach
- Architecture overview
- Key technologies
- Integration points

## Success Criteria
How do we know it's complete?
```

### PRD Workflow Tips

1. **Start with Overview**: Let your AI agent read the entire PRD before creating tasks
2. **Review Before Creating**: Your AI agent will propose structure - review and adjust
3. **Trust AI Breakdown**: AI analyzes technical dependencies intelligently
4. **Iterate as Needed**: Refine features and tasks based on your AI agent's suggestions
5. **Use Templates**: AI automatically applies appropriate templates based on PRD content

> **Advanced**: For very large PRDs, break into sections and work incrementally. Your AI agent can process sections and maintain context across the full scope.

---

## Next Steps

### Learn the System

1. **[AI Guidelines](ai-guidelines)** - How AI agents use Task Orchestrator autonomously
2. **[Templates Guide](templates)** - 9 built-in templates for structured documentation
3. **[Workflow Prompts](workflow-prompts)** - 6 workflow automations for complex scenarios

### Explore Advanced Features

- **Dependencies**: Link related tasks with BLOCKS, IS_BLOCKED_BY relationships
- **Bulk Operations**: Create and manage multiple tasks efficiently
- **Search and Filter**: Find tasks by status, priority, tags, or text
- **Custom Templates**: Create team-specific documentation patterns

### Get Help

- **[Troubleshooting Guide](troubleshooting)** - Solutions to common issues
- **[Installation Guide](installation-guide)** - Detailed setup instructions
- **[API Reference](api-reference)** - Complete tool documentation
- **[GitHub Issues](https://github.com/jpicklyk/task-orchestrator/issues)** - Report bugs or request features

---

## Troubleshooting Quick Tips

**AI can't find the tools?**
- Restart your AI agent after configuration changes
- Verify Docker Desktop is running
- Check JSON syntax with [jsonlint.com](https://jsonlint.com/)

**Docker issues?**
- Ensure Docker Desktop is running: `docker version`
- Verify the image exists: `docker images | grep task-orchestrator`

**Need detailed help?** See the [Troubleshooting Guide](troubleshooting) for comprehensive solutions.

---

**Ready to dive deeper?** Start with [AI Guidelines](ai-guidelines) to understand how AI agents work with Task Orchestrator, or explore [Templates](templates) for structured documentation patterns.
