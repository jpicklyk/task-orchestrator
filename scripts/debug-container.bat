@echo off
echo ===== MCP Task Orchestrator Container Debugging =====

echo Getting container logs...
docker logs mcp-task-orchestrator

echo Checking container status...
docker ps -a -f name=mcp-task-orchestrator

echo Container filesystem information...
docker exec -it mcp-task-orchestrator ls -la /app

echo Checking Java process...
docker exec -it mcp-task-orchestrator ps -ef | grep java

echo ===== End of Debug Info ===== 
