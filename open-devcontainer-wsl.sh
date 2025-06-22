#!/bin/bash

# Script to open DevContainer in WSL2 without switching to Windows

# Get the WSL2 path
WSL_PATH=$(pwd)

# Convert Windows path to WSL path if needed
if [[ "$WSL_PATH" == /mnt/* ]]; then
    # Extract the drive letter and path
    DRIVE=$(echo "$WSL_PATH" | cut -d'/' -f3 | head -c1)
    REST_PATH=$(echo "$WSL_PATH" | cut -d'/' -f4-)
    
    # Copy project to WSL home directory
    WSL_PROJECT_PATH="$HOME/projects/task-orchestrator"
    
    echo "Copying project to WSL filesystem for better performance..."
    mkdir -p "$HOME/projects"
    
    # Use rsync for efficient copying
    rsync -av --exclude='.git' --exclude='build' --exclude='.gradle' \
          --exclude='node_modules' --exclude='.idea' \
          "$WSL_PATH/" "$WSL_PROJECT_PATH/"
    
    cd "$WSL_PROJECT_PATH"
fi

# Open VS Code with DevContainer
echo "Opening DevContainer in WSL2..."
code . --folder-uri "vscode-remote://dev-container+$(printf '%s' "$(pwd)" | xxd -p | tr -d '\n')/workspace"