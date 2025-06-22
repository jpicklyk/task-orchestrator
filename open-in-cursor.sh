#!/bin/bash
# Cursor'da DevContainer açma yardımcı scripti

echo "🚀 Cursor için DevContainer hazırlanıyor..."

# Mevcut container'ları temizle
docker compose -f .devcontainer/docker-compose.yml down 2>/dev/null

# Cursor'ın kendi container'larını temizle
docker ps -a | grep "task-orchestrator_devcontainer" | awk '{print $1}' | xargs -r docker rm -f 2>/dev/null

echo "✅ Temizlik tamamlandı!"
echo ""
echo "📋 Şimdi Cursor'da şu adımları takip et:"
echo ""
echo "1. Cursor'ı aç"
echo "2. File > Open Folder seç"
echo "3. Bu klasörü seç: $(pwd)"
echo "4. Ctrl+Shift+P tuşla"
echo "5. 'Dev Containers: Reopen in Container' seç"
echo ""
echo "💡 Alternatif: Terminal'den aç"
echo "cursor . --folder-uri vscode-remote://dev-container+$(echo -n "$(pwd)" | xxd -p)/workspace"