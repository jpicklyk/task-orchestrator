# ============================================================
# Multi-stage build for MCP Task Orchestrator
# ============================================================

# --- Builder stage ---
FROM eclipse-temurin:23-jdk AS builder

WORKDIR /app

# Gradle wrapper and build configuration (cached aggressively)
COPY gradlew gradlew.bat ./
COPY gradle gradle
COPY build.gradle.kts settings.gradle.kts ./
RUN chmod +x gradlew

# Download dependencies (cached until build files change)
RUN ./gradlew dependencies --no-daemon

# Git for version calculation (git rev-list --count HEAD)
RUN apt-get update && apt-get install -y --no-install-recommends git \
    && rm -rf /var/lib/apt/lists/*
COPY .git .git

# Source code and runtime docs (change frequently, placed last for cache)
COPY src src
COPY docs docs

# Build fat JAR (tests run in CI, skipped here)
RUN ./gradlew build -x test --no-daemon

# --- Runtime stage ---
FROM amazoncorretto:25-al2023-headless

# Amazon Corretto 25 on AL2023 addresses high severity CVEs present in eclipse-temurin:23
# Using headless variant (runtime-only, no GUI libraries) for optimal production container size

LABEL org.opencontainers.image.title="MCP Task Orchestrator" \
      org.opencontainers.image.description="Kotlin MCP server for hierarchical task management with AI assistants" \
      org.opencontainers.image.url="https://github.com/jpicklyk/task-orchestrator" \
      org.opencontainers.image.source="https://github.com/jpicklyk/task-orchestrator" \
      org.opencontainers.image.vendor="jpicklyk" \
      org.opencontainers.image.licenses="MIT"

WORKDIR /app

# Create non-root user and required directories
RUN groupadd -r -g 1001 appgroup \
    && useradd -r -u 1001 -g appgroup -d /app -s /sbin/nologin appuser \
    && mkdir -p /app/data /app/logs \
    && chown -R appuser:appgroup /app

# Copy artifacts from builder
COPY --from=builder --chown=appuser:appgroup /app/build/libs/mcp-task-orchestrator-*.jar /app/orchestrator.jar
COPY --from=builder --chown=appuser:appgroup /app/docs /app/docs

# Volume for SQLite database persistence
VOLUME /app/data

# Environment variables (only those consumed by the application)
ENV DATABASE_PATH=/app/data/tasks.db
ENV MCP_TRANSPORT=stdio
ENV LOG_LEVEL=INFO
ENV USE_FLYWAY=true

# Switch to non-root user
USER appuser

# Run the MCP server
# --enable-native-access=ALL-UNNAMED: Required for SQLite JDBC native library loading in Java 25+
CMD ["java", "-Dfile.encoding=UTF-8", "-Djava.awt.headless=true", "--enable-native-access=ALL-UNNAMED", "-jar", "orchestrator.jar"]
