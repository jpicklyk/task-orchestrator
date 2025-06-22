#!/bin/bash
# DevContainer başlatma scripti

# Container'ı başlat
docker compose -f .devcontainer/docker-compose.yml up -d

# Container ID'sini al
CONTAINER_ID=$(docker compose -f .devcontainer/docker-compose.yml ps -q dev)

echo "DevContainer başlatıldı: $CONTAINER_ID"
echo ""
echo "Container'a bağlanmak için:"
echo "docker exec -it $CONTAINER_ID /bin/bash"
echo ""
echo "Cursor ile açmak için:"
echo "code . --folder-uri vscode-remote://dev-container+$(echo -n "$(pwd)" | xxd -p)/workspace"