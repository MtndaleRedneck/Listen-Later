#!/usr/bin/env bash
# usage: run-fixture.sh stock|custom  (after prepare.sh; builds the repo's own Dockerfile)
set -euxo pipefail
V=$1; HERE=$(cd "$(dirname "$0")" && pwd)
rm -rf fx && mkdir -p fx/fx-data && cd fx
sudo chmod 777 fx-data
cat > docker-compose.yml <<YML
services:
  pigeon-pod:
    image: pp-fixture:$V
    ports: ['8834:8080']
    environment:
      - SPRING_DATASOURCE_URL=jdbc:sqlite:/data/pigeon-pod.db
      - PIGEON_LOG_FILE=/data/logs/pigeon-pod.log
YML
if [ "$V" = custom ]; then
cat >> docker-compose.yml <<YML
      - SPRING_DATA_REDIS_HOST=redis
      - SPRING_DATA_REDIS_PORT=6379
      - SPRING_DATA_REDIS_PASSWORD=fixture-redis-pw
    depends_on: [redis]
  redis:
    image: redis:7
    command: redis-server --requirepass fixture-redis-pw --appendonly yes --appendfsync always
    volumes: [redis-data:/data]
volumes:
  redis-data: {}
YML
sed -i 's#^    environment:#    volumes: [./fx-data:/data]\n    environment:#' docker-compose.yml
else
sed -i 's#^    environment:#    volumes: [./fx-data:/data]\n    environment:#' docker-compose.yml
fi
docker compose up -d
python3 "$HERE/fixture.py" $V || { docker compose logs --tail=200 pigeon-pod; exit 1; }
