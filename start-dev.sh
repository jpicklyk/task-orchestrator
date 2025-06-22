#!/bin/bash
# WSL'de Claude Code ile DevContainer kullanımı

echo "🚀 Task Orchestrator DevContainer Başlatılıyor..."

# Docker servisinin çalıştığından emin ol
if ! docker info > /dev/null 2>&1; then
    echo "❌ Docker çalışmıyor! Lütfen Docker Desktop'ı başlatın."
    exit 1
fi

# Container'ı başlat
docker compose -f .devcontainer/docker-compose.yml up -d

# Container ID'sini al
CONTAINER_ID=$(docker compose -f .devcontainer/docker-compose.yml ps -q dev)

if [ -z "$CONTAINER_ID" ]; then
    echo "❌ Container başlatılamadı!"
    exit 1
fi

echo "✅ DevContainer başarıyla başlatıldı!"
echo ""
echo "📋 Kullanım Seçenekleri:"
echo ""
echo "1. Terminal üzerinden bağlanmak için:"
echo "   docker exec -it $CONTAINER_ID /bin/bash"
echo ""
echo "2. Cursor/VS Code ile açmak için:"
echo "   code . --folder-uri vscode-remote://dev-container+$(echo -n "$(pwd)" | xxd -p)/workspace"
echo ""
echo "3. Container'ı durdurmak için:"
echo "   docker compose -f .devcontainer/docker-compose.yml down"
echo ""
echo "💡 İpucu: Container içindeyken Claude Code kullanmaya devam edebilirsiniz!"