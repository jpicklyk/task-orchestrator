#!/bin/bash
echo "Checking if container is running..."
docker ps -f name=mcp-task-orchestrator

echo "Checking container logs..."
docker logs mcp-task-orchestrator

echo "Checking data directory for SQLite database..."
ls -la data/
