#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
if [[ -f .env ]]; then set -a; source .env; set +a; fi
mkdir -p build/server
javac -encoding UTF-8 -d build/server server/src/*.java
(cd frontend && npm install && npm run build)
echo 'Open http://127.0.0.1:8000'
java -cp build/server CampusDesk 8000
