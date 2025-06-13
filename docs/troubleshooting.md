---
layout: default
title: Troubleshooting
---

# Troubleshooting Guide

This guide covers common issues and their solutions when using MCP Task Orchestrator with Claude Desktop.

## Table of Contents

- [Connection Issues](#connection-issues)
- [Docker Problems](#docker-problems)
- [Configuration Issues](#configuration-issues)
- [JSON Parsing Errors](#json-parsing-errors)
- [Performance Issues](#performance-issues)
- [Debug Mode](#debug-mode)
- [Getting Help](#getting-help)

---

## Connection Issues

### Claude Can't Find MCP Tools

**Symptoms**:
- Claude responds: "I don't have access to task management tools"
- No task-related functionality available
- Commands fail with "unknown tool" errors

**Solutions**:

1. **Restart Claude Desktop**
   ```bash
   # Close Claude Desktop completely
   # Reopen the application
   ```

2. **Verify Configuration File**
   - Check that `claude_desktop_config.json` exists in the correct location
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

Remember: The system is in pre-release status, so occasional issues are expected. Most problems can be resolved with the solutions in this guide.