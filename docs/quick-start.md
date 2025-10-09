---
layout: default
title: Quick Start
---

# Quick Start Guide

Get MCP Task Orchestrator running with Claude Desktop in under 2 minutes. This guide focuses on the fastest path to success.

## Prerequisites

- **Docker Desktop** installed and running
- **Claude Desktop** application, Claude Code, or other AI that supports MCP
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

## Step 3: Configure Claude Desktop

### Find Your Configuration File

**Windows**: `%APPDATA%\Claude\claude_desktop_config.json`
**macOS**: `~/Library/Application Support/Claude/claude_desktop_config.json`
**Linux**: `~/.config/Claude/claude_desktop_config.json`

### Add Task Orchestrator

Open the file and add this configuration:

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

### Restart Claude Desktop

Close and reopen Claude Desktop to load the configuration.

---

## Step 4: Verify the Connection

Open Claude Desktop and ask:

```
Show me an overview of my current tasks
```

Claude should respond confirming the task orchestrator is working (showing no tasks initially).

---

## Step 5: Create Your First Project

### Start Simple

Try these commands in Claude Desktop:

**Create a project**:
```
Create a new project called "My Web App" for building a web application
with user authentication and a dashboard
```

**Add a feature with templates**:
```
Create a feature called "User Authentication" with appropriate templates
```

**Create tasks**:
```
Create tasks for implementing user login, registration, and password reset
```

**View your work**:
```
Show me an overview of my project structure
```

---

## AI Guidelines Initialization

Claude will automatically initialize AI guidelines on first connection to Task Orchestrator. This enables:

- **Autonomous pattern recognition** - Claude understands your intent from natural language
- **Smart template discovery** - Automatically finds and applies appropriate templates
- **Workflow integration** - Seamless use of built-in workflow patterns

> **Learn more**: See [AI Guidelines](ai-guidelines) for details on how Claude uses Task Orchestrator autonomously.

---

## What You Can Do

### Project Organization
- Create hierarchical projects with features and tasks
- Apply templates for consistent documentation
- Track status, priority, and complexity

### Template-Driven Documentation
- 9 built-in templates for common scenarios
- Automatic template discovery by Claude
- Compose multiple templates for comprehensive coverage

### Workflow Automation
- 6 built-in workflow prompts for complex scenarios
- Autonomous pattern application
- Dependency management

### Natural Language Control
Simply ask Claude:
- "Create a task to implement the login API"
- "Show me all high-priority pending tasks"
- "Apply the technical approach template to this task"
- "What should I work on next?"

---

## Working with Existing Projects

Already have a project in progress? Task Orchestrator integrates seamlessly with existing work.

### Connect to Existing Work

**Create a project for your existing codebase**:
```
Create a new project called "My Existing App" to track work on my current application
```

**Import existing work as tasks**:
```
Create tasks for the following work items:
- Fix authentication timeout bug
- Add user profile editing
- Implement API rate limiting
- Update documentation
```

**Organize by features**:
```
Create features to organize these tasks:
- User Management feature
- API Infrastructure feature
- Documentation feature
```

**Link tasks to features**:
```
Move the user-related tasks to the User Management feature
```

Claude will organize your work, apply appropriate templates, and help you track progress on existing initiatives.

---

## PRD-Driven Development Workflow

**Product Requirements Documents (PRDs) provide the most effective workflow** for AI-assisted development. This approach gives Claude comprehensive context for intelligent breakdown and planning.

### Why PRD-Driven Development Works Best

- **Complete Context**: AI analyzes entire requirements at once
- **Intelligent Breakdown**: Automatic decomposition into features and tasks
- **Consistent Documentation**: Templates applied systematically
- **Proper Sequencing**: Dependencies created based on technical requirements
- **Better Results**: AI makes more informed decisions with full context

### Step-by-Step PRD Workflow

**Step 1: Provide Your PRD**

Share your PRD file with Claude:
```
I have a PRD for a new authentication system. Let me share it with you:

[Paste PRD content or share file path]

Please analyze this PRD and break it down into a project structure with features and tasks.
```

**Step 2: Claude Analyzes and Plans**

Claude will:
1. Read and analyze the entire PRD
2. Identify major features and functional areas
3. Create a project structure
4. Break down features into specific tasks
5. Apply appropriate templates for documentation
6. Create dependencies based on technical requirements

**Step 3: Review and Refine**

Claude presents the breakdown:
```
Claude: "I've analyzed your authentication PRD. Here's the proposed structure:

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

Approve and Claude creates:
```
Yes, please create this structure with all templates and dependencies.
```

**Step 5: Start Implementation**

Claude creates everything and you're ready to work:
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

**Claude's Response**:
```
I'll analyze this PRD and create a comprehensive project structure.

[Claude uses get_overview, creates project, creates features with templates,
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

1. **Start with Overview**: Let Claude read the entire PRD before creating tasks
2. **Review Before Creating**: Claude will propose structure - review and adjust
3. **Trust AI Breakdown**: Claude analyzes technical dependencies intelligently
4. **Iterate as Needed**: Refine features and tasks based on Claude's suggestions
5. **Use Templates**: Claude automatically applies appropriate templates based on PRD content

> **Advanced**: For very large PRDs, break into sections and work incrementally. Claude can process sections and maintain context across the full scope.

---

## Next Steps

### Learn the System

1. **[AI Guidelines](ai-guidelines)** - How Claude uses Task Orchestrator autonomously
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

**Claude can't find the tools?**
- Restart Claude Desktop after configuration changes
- Verify Docker Desktop is running
- Check JSON syntax with [jsonlint.com](https://jsonlint.com/)

**Docker issues?**
- Ensure Docker Desktop is running: `docker version`
- Verify the image exists: `docker images | grep task-orchestrator`

**Need detailed help?** See the [Troubleshooting Guide](troubleshooting) for comprehensive solutions.

---

**Ready to dive deeper?** Start with [AI Guidelines](ai-guidelines) to understand how Claude works with Task Orchestrator, or explore [Templates](templates) for structured documentation patterns.
