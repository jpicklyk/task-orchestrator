@echo off
echo ===== Building the MCP Task Orchestrator [Detached Mode] =====

echo Cleaning previous build artifacts...
call gradlew clean

echo Building the application with Gradle...
call gradlew jar

echo Creating data directories...
if not exist data mkdir data
if not exist config mkdir config

echo Stopping any existing containers...
docker-compose down

echo Building Docker image...
docker build -t mcp-task-orchestrator .

echo Running Docker container in detached mode...
docker-compose up -d

echo ===== MCP Task Orchestrator Started in Background =====
echo Use 'docker-compose logs -f' to view logs
