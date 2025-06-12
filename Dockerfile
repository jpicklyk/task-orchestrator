# Multi-stage build - Build stage
FROM eclipse-temurin:21-jdk AS builder

WORKDIR /app

# Install Git (needed for version determination)
RUN apt-get update && apt-get install -y git && rm -rf /var/lib/apt/lists/*
# Copy Git directory as we need the version and build history
COPY .git .git

# Copy Gradle wrapper and build files first
COPY gradlew gradlew.bat ./
COPY gradle gradle
COPY build.gradle.kts settings.gradle.kts ./

# Make gradlew executable
RUN chmod +x gradlew

# Copy source code
COPY src src

# Build using your Gradle wrapper
RUN ./gradlew build --no-daemon

# Runtime stage
FROM amazoncorretto:23-al2023-jdk

WORKDIR /app

# Copy the built JAR from the builder stage
COPY --from=builder /app/build/libs/mcp-task-orchestrator-*.jar /app/orchestrator.jar

# Volume for the SQLite database and configuration
VOLUME /app/data

# Environment variables for configuration
ENV DATABASE_PATH=/app/data/tasks.db
ENV MCP_TRANSPORT=stdio
ENV LOG_LEVEL=info
ENV USE_FLYWAY=true

# Run the application with explicit stdio handling
CMD ["java", "-Dfile.encoding=UTF-8", "-Djava.awt.headless=true", "-jar", "orchestrator.jar"]
