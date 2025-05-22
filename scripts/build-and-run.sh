#!/bin/bash
echo "Building the application with Gradle..."
./gradlew clean jar

echo "Building Docker image..."
docker build -t mcp-task-orchestrator .

echo "Creating data directories..."
mkdir -p data
mkdir -p config

echo "Running Docker container..."
docker-compose up
