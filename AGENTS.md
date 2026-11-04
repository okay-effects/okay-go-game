# AGENTS.md — okay-go-game

The game of Go in a page on okay-ui (screens as data) and okay-faces (the Laminar face). Read `specs/go-game.md` first: the
code follows it, a change of design is a change of the spec first.

- The data model is effects (`Model`, okay-ui's `Ui`); every implementation is a handler; the browser's
  main is one line. Tests swap handlers: no mocks.
- Builds from released libraries only (okay-ui 0.1.0 and what it brings, from their GitHub Pages mavens).
- How the board looks is the skin's (`InTheBrowser.skin`): the program only marks shapes and points.
- Before a commit: `sbt appJVM/test`; after a change to a page, `sbt appJS/web` and `scripts/e2e.scala`.
- Published: `scripts/publish.sh` (the gh-pages branch) → https://okay-effects.github.io/okay-go-game/.
- Scripts are scala-cli, never Python. Scala 3.3, lines at most 120 wide, English in code and docs.
- Commits: plain messages, no attribution trailers.
- Three modules: `core` (the rules and a game's room, shared), `app` (the page), `server` (the games' server:
  `Games` on okay-serverless — Cloudflare from `server/js`, okay-serverless' host from `server/jvm`, the same code).
  The server is deployed by `scripts/deploy.sh` (after `npx wrangler login`); an online change is checked by
  `scripts/e2e-online.scala` on `wrangler dev` (server/worker) before it is deployed.
