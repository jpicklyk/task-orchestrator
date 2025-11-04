---
layout: default
title: Troubleshooting
---

# Troubleshooting Guide

This guide covers common issues and their solutions when using MCP Task Orchestrator with AI coding assistants (Claude Desktop, Claude Code, Cursor, Windsurf, and other MCP-compatible AI agents).

## Table of Contents

- [Quick Reference](#quick-reference)
- [AI Guidelines Issues](#ai-guidelines-issues)
- [Connection Issues](#connection-issues)
- [Docker Problems](#docker-problems)
- [Configuration Issues](#configuration-issues)
- [JSON Parsing Errors](#json-parsing-errors)
- [Performance Issues](#performance-issues)
- [Debug Mode](#debug-mode)
- [Getting Help](#getting-help)

---

## Quick Reference

Top 5 most common issues with one-line fixes:

| Issue | Quick Fix | Details |
|-------|-----------|---------|
| **AI can't find tools** | Restart your AI agent | [Connection Issues](#ai-cant-find-mcp-tools) |
| **Docker not running** | Start Docker Desktop, check with `docker version` | [Docker Problems](#container-wont-start) |
| **Invalid JSON config** | Validate at [jsonlint.com](https://jsonlint.com/) | [Configuration Issues](#invalid-json-syntax) |
| **Container won't start** | Check Docker logs: `docker logs [container-id]` | [Docker Problems](#container-wont-start) |
| **Templates not appearing** | Run `list_templates` to verify, check `isEnabled=true` | [AI Guidelines Issues](#templates-not-being-discovered) |

> **For detailed installation issues**: See the [Installation Guide](installation-guide#troubleshooting-installation)

---

## AI Guidelines Issues

### AI Not Using Patterns Autonomously

**Symptoms**:
- AI asks for explicit instructions instead of recognizing patterns
- Templates aren't being applied automatically
- AI doesn't suggest workflows for complex tasks

**Solutions**:

1. **Verify Initialization**
   - Ask your AI: "Have you initialized the Task Orchestrator guidelines?"
   - Your AI should confirm it has loaded guideline resources
   - If not: "Please initialize Task Orchestrator" (AI will fetch guideline resources)

2. **Check Resource Availability**
   - Ensure MCP server is properly configured and running
   - Verify Task Orchestrator tools are available: "Can you list your available tools?"
   - Confirm your AI can access guideline resources

3. **Provide Explicit Pattern Examples**
   - If autonomous mode isn't working, use explicit guidance:
     - "Use the feature creation pattern for this"
     - "Use the implementation workflow for this bug fix"
     - "Apply the task breakdown workflow"

4. **Re-Initialize if Needed**
   - Ask your AI: "Please re-fetch the Task Orchestrator guidelines"
   - Your AI will reload guideline resources and patterns

> **See**: [AI Guidelines - Troubleshooting](ai-guidelines#troubleshooting) for complete AI-specific issue resolution

---

### Templates Not Being Discovered

**Symptoms**:
- AI creates tasks/features without templates
- Template suggestions aren't happening
- Custom templates aren't being found

**Solutions**:

1. **Verify Templates Exist**
   - Ask your AI: "List available templates"
   - Your AI should use `list_templates` and show all enabled templates
   - Check that expected templates appear in the list

2. **Check Template Status**
   - Verify templates are enabled (not disabled)
   - For custom templates, confirm they're properly created
   - Ask: "Show me templates for TASK entities" or "Show me templates for FEATURE entities"

3. **Ensure AI is Querying Templates**
   - Your AI should automatically run `list_templates` before creating tasks/features
   - If not happening, remind: "Please check for applicable templates first"

4. **Validate Template Targeting**
   - Ensure templates have correct `targetEntityType`
   - TASK templates only apply to tasks
   - FEATURE templates only apply to features

---

### Workflow Not Following Best Practices

**Symptoms**:
- Missing steps in workflow execution
- Incomplete documentation
- Templates not being validated before completion

**Solutions**:

1. **Use Skills or Direct Tools (v2.0)**
   - For critical scenarios (Claude Code):
     - "Use Feature Management Skill to help me plan this feature"
     - "Use Task Management Skill for this implementation"
   - For API-based operations or other clients:
     - Use direct tools: manage_container, query_container
   - For project setup: Use project_setup_workflow

2. **Review Guideline Resources**
   - Ask your AI to refresh pattern understanding
   - Confirm your AI has latest workflow definitions
   - Update custom patterns in project configuration if behavior needs adjustment

3. **Validate Task Completeness**
   - Before marking complete, ask: "Have all template sections been filled?"
   - Your AI should use `get_sections` to validate
   - Ensure summaries, tags, and metadata are complete

> **Learn More**: [AI Guidelines](ai-guidelines) explains how AI discovers patterns and applies workflows autonomously

---

## Connection Issues

### AI Can't Find MCP Tools

**Symptoms**:
- AI responds: "I don't have access to task management tools"
- No task-related functionality available
- Commands fail with "unknown tool" errors

**Solutions**:

1. **Restart Your AI Agent**
   ```bash
   # Close your AI application completely (Claude Desktop, Cursor, etc.)
   # Reopen the application
   ```

2. **Verify Configuration File**
   - Check that your MCP configuration file exists in the correct location
   - For Claude Desktop: `claude_desktop_config.json`
   - For other AI agents: Consult their MCP configuration documentation
   - Ensure the JSON syntax is valid
   - Confirm the `task-orchestrator` entry is properly added

3. **Test Docker Container**
   ```bash
   # Test the container directly (production)
   docker run --rm -i -v mcp-task-data:/app/data ghcr.io/jpicklyk/task-orchestrator:latest
   
   # Or test local build
   docker run --rm -i -v mcp-task-data:/app/data mcp-task-orchestrator:dev
   ```

4. **Check Docker Status**
   ```bash
   # Ensure Docker Desktop is running
   docker version
   docker ps
   ```

### MCP Server Startup Failures

**Symptoms**:
- Container starts but immediately exits
- Connection timeouts
- Empty responses from tools

**Solutions**:

1. **Check Container Logs**
   ```bash
   # Run container with debug output
   docker run --rm -i -v mcp-task-data:/app/data \
     --env MCP_DEBUG=true mcp-task-orchestrator
   ```

2. **Verify Volume Permissions**
   ```bash
   # Check volume exists and is accessible
   docker volume inspect mcp-task-data
   
   # Remove and recreate if needed
   docker volume rm mcp-task-data
   docker volume create mcp-task-data
   ```

3. **Test Basic Connectivity**
   ```bash
   # Test with simple echo
   echo '{"jsonrpc":"2.0","id":1,"method":"tools/list"}' | \
   docker run --rm -i -v mcp-task-data:/app/data mcp-task-orchestrator
   ```

---

## Docker Problems

### Container Won't Start

**Symptoms**:
- Docker run commands fail
- "No such image" errors
- Container exits immediately

**Solutions**:

1. **Verify Image Exists**
   ```bash
   # List available images
   docker images | grep mcp-task-orchestrator
   
   # If missing, rebuild
   ./scripts/docker-clean-and-build.bat  # Windows
   ./scripts/docker-clean-and-build.sh   # Linux/Mac
   ```

2. **Check Docker Desktop Status**
   ```bash
   # Verify Docker is running
   docker version
   docker info
   
   # Restart Docker Desktop if needed
   ```

3. **Clean Docker Environment**
   ```bash
   # Clean up Docker resources
   docker system prune -f
   
   # Remove old containers
   docker container prune -f
   
   # Remove unused volumes
   docker volume prune -f
   ```

### Volume Permission Issues

**Symptoms**:
- Database creation failures
- Permission denied errors
- Data not persisting between runs

**Solutions**:

1. **Recreate Volume**
   ```bash
   # Remove existing volume
   docker volume rm mcp-task-data
   
   # Create fresh volume
   docker volume create mcp-task-data
   ```

2. **Check Volume Mount**
   ```bash
   # Inspect volume configuration
   docker volume inspect mcp-task-data
   
   # Test volume access
   docker run --rm -v mcp-task-data:/test alpine touch /test/test-file
   ```

3. **Alternative Volume Path**
   ```json
   {
     "mcpServers": {
       "task-orchestrator": {
         "command": "docker",
         "args": [
           "run", "--rm", "-i",
           "--volume", "/path/to/local/data:/app/data",
           "mcp-task-orchestrator"
         ]
       }
     }
   }
   ```

### Build Failures

**Symptoms**:
- Docker build commands fail
- Missing dependencies
- Build script errors

**Solutions**:

1. **Clean Build Environment**
   ```bash
   # Clean Gradle cache
   ./gradlew clean
   
   # Remove build artifacts
   rm -rf build/
   ```

2. **Update Dependencies**
   ```bash
   # Refresh Gradle dependencies
   ./gradlew build --refresh-dependencies
   ```

3. **Check System Requirements**
   - Ensure JDK 17+ is installed
   - Verify sufficient disk space
   - Check network connectivity for dependency downloads

---

## Configuration Issues

### Invalid JSON Configuration

**Symptoms**:
- Claude Desktop fails to start
- Configuration errors in logs
- "Invalid JSON" messages

**Solutions**:

1. **Validate JSON Syntax**
   - Use [jsonlint.com](https://jsonlint.com/) to check syntax
   - Ensure all strings use double quotes
   - Check comma placement between entries

2. **Minimal Working Configuration**
   ```json
   {
     "mcpServers": {
       "task-orchestrator": {
         "command": "docker",
         "args": [
           "run", "--rm", "-i",
           "--volume", "mcp-task-data:/app/data",
           "mcp-task-orchestrator"
         ]
       }
     }
   }
   ```

3. **Common JSON Mistakes**
   ```json
   // WRONG - missing comma
   {
     "mcpServers": {
       "server1": { ... }
       "task-orchestrator": { ... }  // Missing comma above
     }
   }
   
   // WRONG - trailing comma
   {
     "mcpServers": {
       "task-orchestrator": { ... }, // Remove trailing comma
     }
   }
   
   // CORRECT
   {
     "mcpServers": {
       "server1": { ... },
       "task-orchestrator": { ... }
     }
   }
   ```

### Configuration File Location

**Common Issues**:
- File in wrong location
- Multiple configuration files
- Permission issues

**Solutions**:

1. **Verify Correct Location**
   - **Windows**: `%APPDATA%\Claude\claude_desktop_config.json`
   - **macOS**: `~/Library/Application Support/Claude/claude_desktop_config.json`
   - **Linux**: `~/.config/Claude/claude_desktop_config.json`

2. **Check File Permissions**
   ```bash
   # Ensure file is readable
   ls -la ~/.config/Claude/claude_desktop_config.json
   
   # Fix permissions if needed
   chmod 644 ~/.config/Claude/claude_desktop_config.json
   ```

3. **Create Missing Directories**
   ```bash
   # Create config directory if missing
   mkdir -p ~/.config/Claude/
   ```

---

## JSON Parsing Errors

### Symptoms

- `"Unexpected number in JSON at position 1"`
- `"Unexpected token 'j', "java.util."... is not valid JSON"`
- Mixed JSON and text output

**Root Cause**: Non-JSON content (like Java stack traces) mixing into the JSON-RPC message stream.

### Solutions

1. **Enable Debug Mode**
   ```bash
   # Add debug flag to Docker args
   --env MCP_DEBUG=true
   ```

2. **Check Application Logs**
   ```bash
   # View detailed logs
   docker run --rm -i -v mcp-task-data:/app/data \
     --env MCP_DEBUG=true mcp-task-orchestrator 2>&1 | tee debug.log
   ```

3. **Isolate the Issue**
   ```bash
   # Test with minimal input
   echo '{"jsonrpc":"2.0","id":1,"method":"tools/list"}' | \
   docker run --rm -i -v mcp-task-data:/app/data mcp-task-orchestrator
   ```

4. **Check for Stack Traces**
   - Look for Java exception traces in output
   - Check system resource availability
   - Verify database file integrity

### Advanced JSON Debugging

1. **Capture Raw Communication**
   ```bash
   # Log all input/output
   docker run --rm -i -v mcp-task-data:/app/data \
     --env MCP_DEBUG=true mcp-task-orchestrator > debug.out 2>&1
   ```

2. **Validate Individual Messages**
   ```bash
   # Test specific tool calls
   echo '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"get_overview","arguments":{}}}' | \
   docker run --rm -i -v mcp-task-data:/app/data mcp-task-orchestrator
   ```

---

## Performance Issues

### Slow Response Times

**Symptoms**:
- Commands take long time to execute
- Timeouts from Claude Desktop
- High resource usage

**Solutions**:

1. **Check System Resources**
   ```bash
   # Monitor container resource usage
   docker stats
   
   # Check host system resources
   top    # Linux/Mac
   # or Task Manager on Windows
   ```

2. **Optimize Database**
   ```bash
   # Check database size
   docker run --rm -v mcp-task-data:/app/data alpine \
     ls -lh /app/data/tasks.db
   
   # Consider database cleanup if very large
   ```

3. **Increase Memory Limits**
   ```json
   {
     "mcpServers": {
       "task-orchestrator": {
         "command": "docker",
         "args": [
           "run", "--rm", "-i",
           "--memory", "1g",
           "--volume", "mcp-task-data:/app/data",
           "mcp-task-orchestrator"
         ]
       }
     }
   }
   ```

### Memory Issues

**Symptoms**:
- Out of memory errors
- Container crashes
- System slowdown

**Solutions**:

1. **Monitor Memory Usage**
   ```bash
   # Check container memory usage
   docker stats --no-stream
   ```

2. **Increase Container Memory**
   ```bash
   # Add memory limit to Docker args
   --memory 2g --memory-swap 2g
   ```

3. **Check for Memory Leaks**
   ```bash
   # Monitor memory over time
   watch docker stats
   ```

---

## Debug Mode

### Enabling Debug Mode

Add debug environment variable to your configuration:

```json
{
  "mcpServers": {
    "task-orchestrator": {
      "command": "docker",
      "args": [
        "run", "--rm", "-i",
        "--volume", "mcp-task-data:/app/data",
        "--env", "MCP_DEBUG=true",
        "mcp-task-orchestrator"
      ]
    }
  }
}
```

### Debug Information

With debug mode enabled, you'll see:
- Detailed request/response logging
- Database operation details
- Template application steps
- Error stack traces
- Performance timing information

### Log Analysis

1. **Look for Error Patterns**
   - JSON parsing errors
   - Database connection issues
   - Template application failures
   - Tool execution errors

2. **Check Tool Call Sequence**
   - Verify correct tool names
   - Check parameter formats
   - Monitor response structures

3. **Database Operations**
   - Monitor SQL query execution
   - Check for constraint violations
   - Verify data integrity

---

## Getting Help

### Self-Help Resources

1. **Documentation**
   - [Quick Start Guide](quick-start)
   - [API Reference](api-reference)
   - [Templates Guide](templates)
   - [Workflow Prompts](workflow-prompts)

2. **Community Resources**
   - [GitHub Issues](https://github.com/jpicklyk/task-orchestrator/issues)
   - [Community Wiki](https://github.com/jpicklyk/task-orchestrator/wiki)
   - [Discussions](https://github.com/jpicklyk/task-orchestrator/discussions)

### Reporting Issues

When reporting issues, include:

1. **System Information**
   ```bash
   # Operating system and version
   uname -a  # Linux/Mac
   # or Windows version
   
   # Docker version
   docker version
   
   # Claude Desktop version
   ```

2. **Configuration**
   - Sanitized `claude_desktop_config.json`
   - Environment variables used
   - Container startup command

3. **Error Details**
   - Complete error messages
   - Debug log output
   - Steps to reproduce
   - Expected vs actual behavior

4. **Debug Output**
   ```bash
   # Capture debug output
   docker run --rm -i -v mcp-task-data:/app/data \
     --env MCP_DEBUG=true mcp-task-orchestrator > debug.log 2>&1
   ```

### Support Channels

- **GitHub Issues**: Bug reports and feature requests
- **Discussions**: Questions and community support
- **Wiki**: Community-contributed guides and examples

### Emergency Recovery

If the system becomes unusable:

1. **Reset Database**
   ```bash
   # Backup existing data
   docker run --rm -v mcp-task-data:/app/data -v $(pwd):/backup alpine \
     cp /app/data/tasks.db /backup/tasks-backup.db
   
   # Remove and recreate volume
   docker volume rm mcp-task-data
   docker volume create mcp-task-data
   ```

2. **Reset Configuration**
   ```bash
   # Backup configuration
   cp claude_desktop_config.json claude_desktop_config.json.backup
   
   # Use minimal configuration
   # (see Configuration Issues section)
   ```

3. **Clean Docker Environment**
   ```bash
   # Complete Docker cleanup
   docker system prune -a
   docker volume prune

   # Rebuild from scratch
   ./scripts/docker-clean-and-build.bat
   ```

---

## Additional Resources

### Related Documentation

- **[Installation Guide](installation-guide)** - Comprehensive setup and installation troubleshooting
- **[Quick Start](quick-start)** - Basic setup verification
- **[AI Guidelines](ai-guidelines)** - AI-specific behavior and patterns
- **[Templates Guide](templates)** - Template system troubleshooting
- **[Workflow Prompts](workflow-prompts)** - Workflow automation issues

### Getting More Help

**GitHub**:
- [Issues](https://github.com/jpicklyk/task-orchestrator/issues) - Bug reports and feature requests
- [Discussions](https://github.com/jpicklyk/task-orchestrator/discussions) - Questions and community support
- [Wiki](https://github.com/jpicklyk/task-orchestrator/wiki) - Community guides and examples

**Pre-Release Status**: The system is in pre-release status (pre-1.0.0), so occasional issues are expected. Most problems can be resolved with the solutions in this guide. For installation-specific issues, see the [Installation Guide - Troubleshooting](installation-guide#troubleshooting-installation) section.