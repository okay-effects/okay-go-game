#!/bin/sh
# THE PAGE PUBLISHED to GitHub Pages (https://okay-effects.github.io/okay-go-game/): tested, linked, its files onto the
# gh-pages branch (index.html, main.js, .nojekyll), pushed
set -e
cd "$(dirname "$0")/.."
# the network's weights (nets/b10c128.bin, not kept in git) written from the text model when missing
[ -f nets/b10c128.bin ] || sbt -batch "netJVM/runMain okay.go.net.WriteBinary"
sbt -batch appJVM/test netJVM/test appJS/web
out=/tmp/okay-go-game-pages
# a worktree left by a run that stopped half way is forgotten first
git worktree prune
rm -rf "$out"
git fetch -q origin gh-pages 2>/dev/null && git worktree add -q "$out" gh-pages || git worktree add -q --orphan -b gh-pages "$out"
rm -f "$out/index.html" "$out/main.js"
cp app/.js/target/web/index.html app/.js/target/web/main.js "$out/"
mkdir -p "$out/net" && cp app/.js/target/web/net/b10c128.bin "$out/net/"
touch "$out/.nojekyll"
git -C "$out" add -A
git -C "$out" commit -q -m "the page, from $(git rev-parse --short HEAD)" || echo "nothing new"
git -C "$out" push -q origin gh-pages
git worktree remove "$out"
echo "published: https://okay-effects.github.io/okay-go-game/"
