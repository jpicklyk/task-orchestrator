# DevContainer Kullanım Kılavuzu

## Hızlı Başlangıç

### 1. DevContainer'ı Başlatma
```bash
./start-dev.sh
```

### 2. Cursor/VS Code ile Açma
DevContainer başladıktan sonra:
- VS Code/Cursor'da: `Ctrl+Shift+P` → "Dev Containers: Reopen in Container"
- Ya da terminal'den: Script'in gösterdiği komutu kullanın

### 3. WSL + Claude Code Çalışma Akışı

#### Senaryo 1: WSL'den DevContainer'a Geçiş
```bash
# WSL'de proje klasöründeyken
./start-dev.sh

# Container'a bağlan
docker exec -it $(docker compose -f .devcontainer/docker-compose.yml ps -q dev) /bin/bash

# Container içinde Claude Code kullan
claude-code
```

#### Senaryo 2: Cursor DevContainer + WSL Claude Code
1. Cursor'da DevContainer'ı aç
2. WSL terminalinde proje klasörüne git
3. Claude Code'u normal şekilde kullan (dosya değişiklikleri senkronize olur)

### 4. Faydalı Komutlar

```bash
# Container durumunu kontrol et
docker compose -f .devcontainer/docker-compose.yml ps

# Container loglarını göster
docker compose -f .devcontainer/docker-compose.yml logs

# Container'ı durdur
docker compose -f .devcontainer/docker-compose.yml down

# Container'ı yeniden başlat
docker compose -f .devcontainer/docker-compose.yml restart

# Container'ı temizle ve yeniden oluştur
docker compose -f .devcontainer/docker-compose.yml down
docker compose -f .devcontainer/docker-compose.yml build --no-cache
docker compose -f .devcontainer/docker-compose.yml up -d
```

### 5. Sorun Giderme

#### Docker çalışmıyor hatası
```bash
# Docker Desktop'ı başlat
# Windows'ta sistem tepsisinden Docker Desktop'ı aç
```

#### Container başlamıyor
```bash
# Container'ı temizle
docker compose -f .devcontainer/docker-compose.yml down -v
docker compose -f .devcontainer/docker-compose.yml build --no-cache
```

#### Port çakışması
.devcontainer/docker-compose.yml dosyasında portları değiştir:
```yaml
ports:
  - "3200-3202:3100-3102"  # Sol taraf host, sağ taraf container
```

### 6. Claude Code Entegrasyonu

Container içindeyken Claude Code kullanmak için:
1. Container'a bağlan
2. Proje klasörüne git: `cd /workspace`
3. Claude Code'u başlat: `claude-code` (eğer yüklüyse)

WSL'den dosyaları düzenlerken:
- Dosya değişiklikleri otomatik olarak container'a yansır
- Volume mount sayesinde senkronizasyon otomatiktir