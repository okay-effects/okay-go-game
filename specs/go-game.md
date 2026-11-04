# okay-go-game — the game of Go in a page, on okay-ui and okay-faces (spec, 2026-11-04)

(Not `okay-go`: that name is the okay-effects library for the Go language.)

## The rule

The data model is effects; every implementation is a handler; the browser runs the application in ONE line.

| effect | operation | answered with | handlers |
|---|---|---|---|
| `Remote` | `Create`, `Join`, `Updates`, `Send`, `Leave` | a game far apart: its invite, its updates | `Sockets(server)` (the page), `Tables().client()` (tests) |
| `Place` | `Read`, `Write`, `Base` | the page's address: a game's link | `PageAddress()` (the page), `Place.fixed` (tests) |
| `Model` | `Logits(position)` | a logit for each of the 81 points, then one for passing | `Model.toy()` (the page, tests with a seed) |
| `Ui` | `Show(screen)` | the button pressed | okay-ui's `steps` |

```scala
Page.mount(OnFaces.program(steps(PageAddress()(Sockets(Sockets.server)(Model.toy()(Go.main[App]))))), skin)
```

## The rules (`Rules`)

9 × 9; Black begins. A group with no liberty is captured; a stone whose group would have no liberty (capturing
nothing) is suicide and not allowed; no move may bring back a board seen before (positional superko — it covers ko).
A pass instead of a stone; two passes in a row end the game. The score by area (Tromp-Taylor): stones plus the empty
points whose region reaches only that colour; komi 6.5 to White, so no draw. Dead stones are not marked: they are
captured before passing, the board counted as it stands. Points named by column (A–J, no I) and row from the bottom.

## The game (`Go`)

- The menu: Two players, Play a friend online, Play Black against the model, Play White against the model, Rules. No Quit.
- The board (`Board`): `Part.block("go")` of a picture — the wood, 9 lines each way, the star points, the stones, the
  last move's dot, at the end each empty point's owner — and over it `go-points-<colour to play>`, a grid of 81
  blocks with a button at each LEGAL point only (named by the point, E5): a taken point, a suicide or a ko cannot be
  asked for. The skin draws the button as a round shadow of the stone to be played under the pointer.
- A turn: the status (who plays, the move, captures), Pass, Resign, Menu. Against the model, after the person's move
  the model moves at once: its logits; what it WANTED (the highest of all 82) and what it PLAYED (the highest legal
  point, or a pass) — shown with the reason when they differ ("D5 — not allowed: the point is taken").
- The end: two passes — the result, Black's and White's counts; or a resignation.

## Online: two people far apart, and watchers (operator, 2026-11-04)

- The menu's "Play a friend online": the creator chooses a colour; the page makes the game's id and both players'
  keys (16 random bytes each, `crypto.getRandomValues`) and shows two links — the friend's (`#g=<id>&k=<their key>`,
  the other colour) and the watchers' (`#g=<id>`). The page's own address becomes the creator's link (`Place`), so a
  reload keeps the seat. A page opened by a link goes straight to that game.
- The server (`server/`, okay-serverless): a game a Durable Object (`Games.Table`, an `Actor`) named by its id; the
  Worker (`Games.Lobby`) sends /games/<id>/socket to it as it is. The first socket that brings both keys
  (`?key=…&black=…&white=…`) makes the game; from then a socket's key is its seat, any other key or none watches —
  the keys once kept are never replaced. Sockets are tagged by seat (black, white, watch). An action
  (`{"move":k}`, `{"pass":true}`, `{"resign":true}`) is checked by `Room.act` — the same rules as the page's: not
  over, not a watcher, its turn (a resignation any time), a legal point — kept in one `Durable.step`, then every
  socket told the game (`Room.View`: the moves, the resignation, its own seat, who of the players is here, how many
  watch); a refused action is told to its socket alone, with the reason. A socket closing tells the others.
- The page (`Online`): the effect `Remote` (Create, Join, Updates, Send, Leave) — over a WebSocket in the page
  (`Sockets`: what is sent before the socket opens is kept; a socket that closes without a Leave opens again after
  1.5 s), in memory in tests (`Tables`). The screen waits for the server (`Screen.until` its updates): a person's
  move is sent and the board waits for what the server says; buttons only on the person's turn, at legal points; a
  watcher has only Leave. The status says who plays and who waits, who is here, how many watch; at the end the
  result.
- Where: Cloudflare (main) — https://okay-go-game.okay-effects.workers.dev, `scripts/deploy.sh`; the page's server is
  `?server=` (default that Worker). The host (the fallback, the same code): `sbt serverJVM/run` (`OKAY_GO_DIR`,
  `PORT`, `HOST`), the page served by it too when built — `http://127.0.0.1:8787/?server=ws://127.0.0.1:8787`.
- Checked: `GamesSuite` (the server on `MemoryCloud`: made by the first socket, seats, a move told to all, out of
  turn / a watcher's / illegal refused with the reason, over, a resignation kept for a later socket, keys not taken,
  a socket gone); `OnlineSuite` (two people's programs and a watcher's on one `Tables`: the links, waiting, moves
  seen, a resignation; Leave clears the address); `scripts/e2e-online.scala` — three browsers (Black, White by the
  friend's link, a watcher) to the end, a reload keeping the seat: on `wrangler dev`, on the live Worker with the
  live page, and on the host.

## The toy model (`Model.W`, twelve numbers)

Each point is valued as if a stone of the side to play stood there — it does not know which points are legal; the
mask does: per stone captured +3, an enemy group put in atari +1.2, an own group in atari saved +2.5, self-atari −2.5,
filling an own eye −6, a point in a small (≤ 10) region already its own −3 or the opponent's −1.5, the first line −1,
the third and fourth lines +0.6 in the first 20 moves, +0.3 per stone next to it, noise up to 0.6; passing 0.2.
Toy against toy: games of 63–77 moves with captures, ended by two passes (5 seeds). It plays like a beginner.

## Checks

- `sbt appJVM/test` — `GoSuite`: names; a capture counted; suicide refused, a capturing move allowed; ko refused at
  once and allowed later; the score of an empty board and of walls; two players by buttons (a taken point has no
  button, two passes end it); against the toy (what it wanted and played; 150 moves of the toy all legal); as White
  the model begins.
- `scala-cli run scripts/e2e.scala -- app/.js/target/web/index.html`: two players in Chromium to the end, then against
  the model; pictures in /tmp. Published by `scripts/publish.sh`: https://okay-effects.github.io/okay-go-game/.
