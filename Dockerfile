# ============================================================
# Multi-stage build for MCP Task Orchestrator
# ============================================================

# --- Builder stage ---
FROM eclipse-temurin:21-jdk AS builder

WORKDIR /app

# Gradle wrapper and build configuration (cached aggressively)
COPY gradlew gradlew.bat ./
COPY gradle gradle
COPY build.gradle.kts settings.gradle.kts ./
COPY version.properties ./
COPY current/build.gradle.kts current/
RUN chmod +x gradlew

# Download dependencies (cached until build files change)
RUN ./gradlew dependencies --no-daemon

# Source code (changes frequently, placed last for cache)
COPY current/src current/src

RUN ./gradlew :current:jar --no-daemon -x test

# --- Runtime base stage (shared configuration for all runtime targets) ---
FROM amazoncorretto:25-al2023-headless AS runtime-base

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
# shadow-utils provides groupadd/useradd on AL2023 minimal images
RUN dnf install -y shadow-utils \
    && dnf clean all \
    && groupadd -r -g 1001 appgroup \
    && useradd -r -u 1001 -g appgroup -d /app -s /sbin/nologin appuser \
    && mkdir -p /app/data /app/logs \
    && chown -R appuser:appgroup /app

# Volume for SQLite database persistence
VOLUME /app/data

# Environment variables (only those consumed by the application)
ENV DATABASE_PATH=/app/data/tasks.db
ENV MCP_TRANSPORT=stdio
ENV LOG_LEVEL=INFO
ENV USE_FLYWAY=true
ENV AGENT_CONFIG_DIR=/project
ENV MCP_HTTP_HOST=0.0.0.0
ENV MCP_HTTP_PORT=3001
EXPOSE 3001

# Switch to non-root user
USER appuser

# Signal Docker to send SIGTERM for graceful shutdown
STOPSIGNAL SIGTERM

# Run the MCP server
# --enable-native-access=ALL-UNNAMED: Required for SQLite JDBC native library loading in Java 25+
CMD ["java", "-Dfile.encoding=UTF-8", "-Djava.awt.headless=true", "--enable-native-access=ALL-UNNAMED", "-jar", "orchestrator.jar"]

# --- runtime-current target (v3 Current — active) ---
FROM runtime-base AS runtime-current
COPY --from=builder --chown=appuser:appgroup /app/current/build/libs/mcp-task-orchestrator-current-*.jar /app/orchestrator.jar
ENV DATABASE_PATH=data/current-tasks.db
