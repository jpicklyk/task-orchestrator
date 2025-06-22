# Base image with JDK 17
FROM mcr.microsoft.com/devcontainers/java:17-bullseye

# Install Kotlin using existing SDKMAN installation
RUN bash -c "source /usr/local/sdkman/bin/sdkman-init.sh && sdk install kotlin"

# Install additional development tools
RUN apt-get update && export DEBIAN_FRONTEND=noninteractive \
    && apt-get -y install --no-install-recommends \
    curl \
    wget \
    zip \
    unzip \
    sqlite3 \
    libsqlite3-dev \
    && apt-get clean \
    && rm -rf /var/lib/apt/lists/*

# Install Node.js for MCP testing (optional)
RUN curl -fsSL https://deb.nodesource.com/setup_lts.x | bash - \
    && apt-get install -y nodejs

# Set GRADLE_USER_HOME to workspace directory
ENV GRADLE_USER_HOME=/workspace/.gradle

# Create gradle cache directory
RUN mkdir -p /workspace/.gradle

# Set working directory
WORKDIR /workspace

# Copy gradle wrapper files first for better caching
COPY gradlew gradlew.bat /workspace/
COPY gradle /workspace/gradle

# Make gradlew executable and fix line endings
RUN chmod +x /workspace/gradlew && \
    apt-get update && apt-get install -y dos2unix && \
    dos2unix /workspace/gradlew && \
    rm -rf /var/lib/apt/lists/*

# Pre-download gradle wrapper
RUN cd /workspace && ./gradlew --version

# Switch to non-root user
USER vscode