#!/bin/bash
# Cursor'a container bağlantısı için yardımcı script

echo "🔌 Cursor DevContainer Bağlantısı"
echo ""

# Container'ı başlat
echo "📦 Container başlatılıyor..."
docker compose -f .devcontainer/docker-compose.yml up -d

# Container ID'sini al
CONTAINER_ID=$(docker compose -f .devcontainer/docker-compose.yml ps -q dev)

if [ -z "$CONTAINER_ID" ]; then
    echo "❌ Container başlatılamadı!"
    exit 1
fi

echo "✅ Container başlatıldı: $CONTAINER_ID"
echo ""

# Cursor için URL oluştur
WORKSPACE_PATH=$(pwd)
ENCODED_PATH=$(echo -n "$WORKSPACE_PATH" | xxd -p | tr -d '\n')

echo "📋 Cursor'da Açma Seçenekleri:"
echo ""
echo "🔄 Yöntem 1: Dev Container Extension ile"
echo "1. Cursor'da Extensions (Ctrl+Shift+X) aç"
echo "2. 'Dev Containers' ara ve Microsoft'un extension'ını yükle"
echo "3. Ctrl+Shift+P → 'Dev Containers: Open Folder in Container'"
echo "4. Bu klasörü seç: $WORKSPACE_PATH"
echo ""
echo "🔄 Yöntem 2: Remote Development Extension ile"
echo "1. 'Remote Development' extension'ını yükle"
echo "2. Ctrl+Shift+P → 'Remote-Containers: Attach to Running Container'"
echo "3. 'task-orchestrator-devcontainer' seç"
echo ""
echo "🔄 Yöntem 3: Manuel Terminal"
echo "Terminal'de çalıştır:"
echo "docker exec -it $CONTAINER_ID /bin/bash"
echo ""
echo "🔄 Yöntem 4: VSCode ile"
echo "code --folder-uri vscode-remote://dev-container+$ENCODED_PATH/workspace"
echo ""
echo "📍 Container bilgileri:"
echo "Adı: task-orchestrator-devcontainer"
echo "ID: $CONTAINER_ID"
echo "Workspace: /workspace"