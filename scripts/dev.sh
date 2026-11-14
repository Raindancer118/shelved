#!/usr/bin/env bash
# Local dev: Postgres in Docker, app with profile "local" (no SSO), keys from the gitignored .env.* files.
set -euo pipefail
cd "$(dirname "$0")/.."
if ! docker ps --format '{{.Names}}' | grep -qx shelved-db; then
  docker start shelved-db >/dev/null 2>&1 || docker run -d --name shelved-db -p 127.0.0.1:5433:5432 \
    -e POSTGRES_DB=shelved -e POSTGRES_USER=shelved -e POSTGRES_PASSWORD=shelved \
    -v shelved-db:/var/lib/postgresql/data postgres:17-alpine >/dev/null
  until docker exec shelved-db pg_isready -U shelved >/dev/null 2>&1; do sleep 1; done
fi
set -a
for f in .env.anthropic .env.google .env.hardcover; do [ -f "$f" ] && . "./$f"; done
set +a
exec mvn -q spring-boot:run -Dspring-boot.run.profiles=local -Dspring-boot.run.arguments=--server.port=${PORT:-8150}
