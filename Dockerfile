FROM amazoncorretto:23-al2023-jdk

WORKDIR /app

# Copy the application JAR
COPY ./build/libs/mcp-task-orchestrator-*.jar /app/orchestrator.jar

# Volume for the SQLite database and configuration
VOLUME /app/data

# Environment variables for configuration
ENV DATABASE_PATH=/app/data/tasks.db
ENV MCP_TRANSPORT=stdio
ENV LOG_LEVEL=info

# Run the application with explicit stdio handling
CMD ["java", "-Dfile.encoding=UTF-8", "-Djava.awt.headless=true", "-jar", "orchestrator.jar"]
