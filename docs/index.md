---
layout: default
title: Home
---

# MCP Task Orchestrator Documentation

Welcome to the comprehensive documentation for MCP Task Orchestrator - an AI-native project management system built on the Model Context Protocol.

## 🚀 Get Started Quickly

<div class="grid-container">
  <div class="grid-item">
    <h3>⚡ <a href="quick-start">Quick Start</a></h3>
    <p>Get running with Docker and Claude Desktop in 2 minutes</p>
  </div>
  <div class="grid-item">
    <h3>🔧 <a href="installation-guide">Installation Guide</a></h3>
    <p>Comprehensive setup for all platforms and scenarios</p>
  </div>
  <div class="grid-item">
    <h3>🤖 <a href="ai-guidelines">AI Guidelines</a></h3>
    <p>AI initialization and autonomous workflow patterns</p>
  </div>
  <div class="grid-item">
    <h3>📋 <a href="templates">Templates</a></h3>
    <p>9 built-in workflow templates (instructions, frameworks, quality gates)</p>
  </div>
  <div class="grid-item">
    <h3>🔄 <a href="workflow-prompts">Workflow Prompts</a></h3>
    <p>6 workflow automations for common scenarios</p>
  </div>
  <div class="grid-item">
    <h3>🆘 <a href="troubleshooting">Troubleshooting</a></h3>
    <p>Solutions to common issues and problems</p>
  </div>
</div>

## What is MCP Task Orchestrator?

MCP Task Orchestrator is a Kotlin-based Model Context Protocol (MCP) server that provides AI assistants with sophisticated project management capabilities. It's designed to be context-efficient, template-driven, and specifically optimized for AI workflows.

### Key Benefits

- **🤖 AI-Native Design**: Built specifically for AI assistant interactions
- **📊 Hierarchical Organization**: Projects → Features → Tasks with dependencies
- **🎯 Context-Efficient**: Progressive loading and token optimization
- **📋 Template-Driven**: 9 built-in workflow templates with decision frameworks and quality gates
- **🔄 Workflow Automation**: 6 comprehensive workflow prompts
- **⚡ Complete API**: Comprehensive MCP tools for full project orchestration

## Core Concepts

```
Project (optional)
  └── Feature (optional)
      └── Task (required) ←→ Dependencies → Task
          └── Section (optional, detailed content)
```

- **Projects**: Top-level organizational containers for large initiatives
- **Features**: Group related tasks into cohesive functional units
- **Tasks**: Primary work units with status, priority, and complexity tracking
- **Dependencies**: Model relationships between tasks (BLOCKS, IS_BLOCKED_BY, RELATES_TO)
- **Sections**: Rich content blocks for detailed documentation
- **Templates**: Workflow instructions, decision frameworks, and quality gates for implementation guidance

## Getting Started Path

New to Task Orchestrator? Follow this path for the best experience:

1. **[Quick Start](quick-start)** (2 minutes) - Get Docker image and configure Claude Desktop
2. **[AI Guidelines](ai-guidelines)** (5 minutes) - Understand how Claude uses the system autonomously
3. **Create your first project** - Ask Claude: *"Create a new project for my web application"*
4. **[Templates](templates)** (10 minutes) - Learn about workflow templates and decision frameworks
5. **[Workflow Prompts](workflow-prompts)** - Explore workflow automations for complex scenarios

## Documentation Structure

### For New Users
- **[Quick Start Guide](quick-start)** - Get up and running in 2 minutes
- **[AI Guidelines](ai-guidelines)** - How Claude works with Task Orchestrator
- **[Installation Guide](installation-guide)** - Detailed setup for all platforms

### For Power Users
- **[Templates System](templates)** - Built-in template reference and customization
- **[Workflow Prompts](workflow-prompts)** - 6 comprehensive workflow automations
- **[API Reference](api-reference)** - Complete tool documentation

### For Developers
- **[Developer Guides](developer-guides)** - Architecture, contributing, and development setup
- **[Database Migrations](developer-guides/database-migrations)** - Schema change management guide
- **[Troubleshooting](troubleshooting)** - Comprehensive problem resolution
- **[GitHub Repository](https://github.com/jpicklyk/task-orchestrator)** - Source code and contributing

## Quick Examples

### Natural Language Control

Ask Claude directly - no complex commands needed:

```
"Create a new project for my web application with user authentication and payment features"
```

Claude will:
- Create the project
- Set up appropriate structure
- Apply relevant templates automatically
- Confirm creation with next steps

```
"Show me all high-priority tasks that are pending"
```

Claude will:
- Search tasks by priority and status
- Present organized results
- Suggest what to work on next

```
"Create a task to implement the login API with technical approach and testing templates"
```

Claude will:
- Create the task
- Apply requested templates
- Set appropriate metadata
- Link to related features if applicable

### Template-Driven Workflow

Templates provide workflow guidance and decision frameworks:

```
"Apply the bug investigation workflow to this authentication issue"
```

Claude will apply the template with sections for:
- Problem description
- Investigation steps
- Root cause analysis
- Resolution plan
- Testing strategy

### Dependency Management

```
"Task A blocks task B because the API must be ready first"
```

Claude creates the dependency relationship and understands:
- Execution order constraints
- What can be worked on in parallel
- Blocking relationships in the critical path

### PRD-Driven Development ⭐ Most Effective

```
"Analyze this PRD and create a complete project structure:

# Payment Processing System PRD
[PRD content...]"
```

Claude will:
- Read and analyze the entire PRD
- Identify major features and functional areas
- Create project with features and tasks
- Apply appropriate templates systematically
- Establish dependencies based on technical requirements
- Present complete breakdown with recommended implementation sequence

> **Why it works best**: Complete context enables intelligent breakdown, proper sequencing, and optimal template application. See [PRD Workflow Guide](quick-start#prd-driven-development-workflow) for detailed instructions.

## Community Resources

- **[GitHub Repository](https://github.com/jpicklyk/task-orchestrator)** - Source code and issues
- **[Community Wiki](https://github.com/jpicklyk/task-orchestrator/wiki)** - Examples and community guides
- **[Discussions](https://github.com/jpicklyk/task-orchestrator/discussions)** - Ask questions and share ideas

---

<style>
.grid-container {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(250px, 1fr));
  gap: 20px;
  margin: 30px 0;
}

.grid-item {
  border: 1px solid #e1e4e8;
  border-radius: 6px;
  padding: 20px;
  background: #f6f8fa;
}

.grid-item h3 {
  margin-top: 0;
  color: #0366d6;
}

.grid-item p {
  margin-bottom: 0;
  color: #586069;
}
</style>
