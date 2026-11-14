#!/usr/bin/env bash
# Creates /home/murthag/shelved/.env with a fresh DB password. An existing .env is never touched.
# Run on the target server: ./prepare-server.sh
set -euo pipefail
DIR=/home/murthag/shelved
mkdir -p "$DIR"
if [[ -f "$DIR/.env" ]]; then
  echo ".env already exists, nothing changed."
  exit 0
fi
umask 077
sed -e "s|^DB_PASSWORD=.*|DB_PASSWORD=$(openssl rand -hex 24)|" "$(dirname "$0")/../.env.example" > "$DIR/.env"
echo "Created $DIR/.env. Fill in OIDC client, Anthropic and Google Books keys, then deploy."
