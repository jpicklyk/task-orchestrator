---
layout: default
title: Home
---

# MCP Task Orchestrator Documentation

Welcome to the comprehensive documentation for MCP Task Orchestrator - an AI-native project management system built on the Model Context Protocol.

## 🚀 Get Started Quickly

<div class="grid-container">
  <div class="grid-item">
    <h3>📖 <a href="quick-start">Quick Start</a></h3>
    <p>Get running with Docker and Claude Desktop in 2 minutes</p>
  </div>
  <div class="grid-item">
    <h3>🔧 <a href="api-reference">API Reference</a></h3>
    <p>Complete reference for all 37 MCP tools</p>
  </div>
  <div class="grid-item">
    <h3>📋 <a href="workflow-prompts">Workflow Prompts</a></h3>
    <p>6 built-in workflow automations for common scenarios</p>
  </div>
  <div class="grid-item">
    <h3>📝 <a href="templates">Templates</a></h3>
    <p>9 built-in templates for consistent documentation</p>
  </div>
</div>

## What is MCP Task Orchestrator?

MCP Task Orchestrator is a Kotlin-based Model Context Protocol (MCP) server that provides AI assistants with sophisticated project management capabilities. It's designed to be context-efficient, template-driven, and specifically optimized for AI workflows.

### Key Benefits

- **🤖 AI-Native Design**: Built specifically for AI assistant interactions
- **📊 Hierarchical Organization**: Projects → Features → Tasks with dependencies  
- **🎯 Context-Efficient**: Progressive loading and token optimization
- **📋 Template-Driven**: Consistent documentation with 9 built-in templates
- **🔄 Workflow Automation**: 5 comprehensive workflow prompts
- **⚡ Complete API**: 37 MCP tools for full project orchestration

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
- **Templates**: Standardized patterns for consistent project documentation

## Documentation Structure

### For New Users
- **[Quick Start Guide](quick-start)** - Get up and running immediately
- **[Core Concepts](#core-concepts)** - Understand the data model

### For Developers  
- **[API Reference](api-reference)** - Complete tool documentation
- **[Database Migrations](database-migrations)** - Schema change management guide
- **[Templates System](templates)** - Built-in template reference
- **[Troubleshooting](troubleshooting)** - Common issues and solutions

### For Project Managers
- **[Workflow Prompts](workflow-prompts)** - Comprehensive workflow automations
- **[Best Practices](#)** - Recommended usage patterns (coming soon)

## Community Resources

- **[GitHub Repository](https://github.com/jpicklyk/task-orchestrator)** - Source code and issues
- **[Community Wiki](https://github.com/jpicklyk/task-orchestrator/wiki)** - Examples and community guides
- **[Discussions](https://github.com/jpicklyk/task-orchestrator/discussions)** - Ask questions and share ideas

## Quick Examples

### Create Your First Project
```
Ask Claude: "Create a new project for my web application with user authentication and payment features"
```

### Apply Templates
```
Ask Claude: "Apply the technical approach template to this task and include testing strategy"
```

### Use Workflow Prompts
```
Ask Claude: "Use the sprint planning workflow to organize my current backlog"
```

## Pre-Release Notice

**⚠️ Current Version: Pre-1.0.0 (Development)**

This project is actively being developed toward a 1.0.0 release. The SQL database schema may change between updates. For production use, please wait for the stable 1.0.0 release.

- 🔔 [Watch for releases](https://github.com/jpicklyk/task-orchestrator/releases)
- 📋 [View changelog](https://github.com/jpicklyk/task-orchestrator/blob/main/CHANGELOG.md)

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