#!/bin/sh
# THE GAMES' SERVER ON CLOUDFLARE (https://okay-go-game.okay-effects.workers.dev): the Worker linked
# (`sbt serverJS/worker`) and deployed, after `npx wrangler login`; then the page published (scripts/publish.sh)
set -eu
here=$(cd "$(dirname "$0")/.." && pwd)
cd "$here"
sbt -batch serverJVM/test serverJS/worker
cd server/worker
[ -d node_modules ] || npm install --silent
npx wrangler whoami | grep -q "associated with" || { echo "npx wrangler login first"; exit 1; }
npx wrangler deploy
