# okay-go-game — the game of Go in a page, on okay-ui and okay-faces (spec, 2026-11-04)

(Not `okay-go`: that name is the okay-effects library for the Go language.)

## The rule

The data model is effects; every implementation is a handler; the browser runs the application in ONE line.

| effect | operation | answered with | handlers |
|---|---|---|---|
| `Model` | `Think(position)` | a `Thought`: a point or a pass, and words | `Thinking()` into `Async` (the page), `Model.searching(r, games)` (tests) |
| `Remote` | `Create`, `Join`, `Updates`, `Send`, `Leave` | a game far apart: its invite, its updates | `Sockets(server)` (the page), `Tables().client()` (tests) |
| `Place` | `Read`, `Write`, `Base` | the page's address: a game's link | `PageAddress()` (the page), `Place.fixed` (tests) |
| `Clipboard` | `Copy(text)` | whether it was copied | okay-ui's `InThePage.clipboard` (the page), `copiedTo` (tests) |
| `Ui` | `Show(screen)` | the button pressed, or the screen's event | okay-ui's `steps` |

```scala
// the page's row: okay-scala's Async first, then the application's
val world = InThePage.clipboard(PageAddress()(Sockets(Sockets.server)(Thinking()(Go.main[Web]))))
Async.start(Laminar.run(dom.document.getElementById("app"), skin)(world))
```

One program of one async effect, run by okay-ui's `Laminar.run`: the screens and their events (a game far apart's
socket messages, `Screen.until`) on okay-faces' Laminar face, the model's thinking an `Async` wait. (Before
2026-11-04 the page was `Page.mount` and the model answered by an event: okay-faces' faces kept listening after an
event until the next screen, so `Laminar.run` looked as if it could not take events; okay-faces 0.1.0's web faces
were republished with the fix, okay-ui's `EventsSuite` checks it.)

## The boards and the rules (`Geometry`, `Rules`, in `core`)

9 × 9, 13 × 13 or 19 × 19 (`Geometry`: points named by column — A to T, no I — and row from the bottom; the star
points; komi 6.5 on 9 × 9, 7.5 on the larger boards). Black begins. A group with no liberty is captured; a stone whose
group would have no liberty (capturing nothing) is suicide and not allowed; no move may bring back a board seen before
(positional superko — it covers ko). A pass instead of a stone; two passes in a row end the game. The score by area
(Tromp-Taylor): stones plus the empty points whose region reaches only that colour, komi to White, so no draw. Dead
stones are not marked: they are captured before passing, the board counted as it stands.

## The game (`Go`)

- The menu: Two players, Play a friend online, Play Black against the model, Play White against the model, Rules.
  No Quit. Each game asks its board first: 9 × 9 — a quick game, 13 × 13, 19 × 19 — the full board.
- The board (`Board`): `Part.block("go")` of a picture — the wood, the lines, the star points, the stones, the last
  move's dot, at the end each empty point's owner — and over it `go-points-<colour to play>-<size>`, a grid of
  blocks with a button at each LEGAL point only (named by the point, E5): a taken point, a suicide or a ko cannot be
  asked for. The skin draws the button as a round shadow of the stone to be played under the pointer, and sizes the
  grid for each board (`[class$="-<size>"]`).
- A turn: the status (who plays, the move, captures), Pass, Resign, Menu. Against the model, after the person's move
  the model thinks on a WORKING screen — the board, "The model is thinking…" — in an `interruptible` block whose exit
  is Menu: pressed, the model's `Async` wait is cancelled (the search stops) and the game left; then "The model
  played" and "The model thinks" (its chance to win, the games it played out).
- The end: two passes — the result, Black's and White's counts with the board's komi; or a resignation.

## The model: a search (`Searcher`, `Fast`; operator, 2026-11-04: one model, strong)

Monte-Carlo tree search with RAVE and prior knowledge, on a fast board of any size (`Fast`: arrays, marks, simple ko).

- The fast board keeps a list of its empty points (a random move picked from it, not from every point), and a
  self-atari is looked for only when the cheap look (two empty neighbours, no friend) does not settle it: on the JVM
  ~22 000 games a second on 9 × 9, ~4 900 on 19 × 19 (2 170 before).
- A game played out: down the tree by the best estimate — the share of wins mixed with RAVE's share for the same move
  anywhere later (weight sqrt(800 / (3n + 800))), plus 0.2·sqrt(ln N / n) — then by the playout policy to the end:
  a capture next to the last move; else an own group next to it saved from atari; else at random, no own eye filled,
  a self-atari refused nine times in ten. Scored by stones and eyes, komi the board's.
- Prior knowledge: a new node's moves start with virtual games (8; 20 on 13 × 13 and 19 × 19) in BOTH estimates —
  the share of wins and RAVE's, else RAVE, most of the value while a move has few games, hides it — their share by
  what the move does: a capture 0.8, saving a group 0.75, a self-atari 0.15, an atari 0.6, else 0.5; the first line
  −0.15 (−0.25 on a large board); in the opening the third and fourth lines +0.1 (+0.15), the second −0.05 (−0.15);
  near the last move +0.05. THE OPENING ON A LARGE BOARD (operator, 2026-11-04: "strange moves on 19 × 19"): an empty
  corner's third or fourth line +0.05, a contact without a tactical reason −0.05, the far centre −0.05. The root's
  moves are the exact rules' (superko).
- The answer (`Model.answer`): the point played most. A pass when nothing is worth playing, when the opponent has
  passed and the board as it stands is won, or when its chance is under 4% after 500 games.
- In the page (`Thinking`, `Model` into `Async.cancellable`): parts of ~25 ms between the page's frames until the
  board's budget (`Model.budget`): at
  most 30 000 games, and 1.5 s on 9 × 9, 3 s on 13 × 13, 5 s on 19 × 19. Measured in Chromium: ~17 500 games a move
  on 9 × 9 in 1.5 s, ~12 500 on 19 × 19 in 5 s (11 000 and 4 800 before the empty points' list).
- Measured against the search before it (MCTS-RAVE, light playouts, no priors) on 9 × 9: at 400 games a move 27 of 30
  (25 of 30 before the atari priors; without priors 14 of 30 — the priors are most of it); at 2000 games 14 of 20;
  heavy playouts against light at 2000 games 13 of 20; at the page's budgets (12 000 games against its 4000) 9 of 10.
- After "strange moves on 19 × 19": on 19 × 19 at 300 ms a move the faster search with the opening knowledge won 3 of 3
  against the one before (that one opened on the second line, the first line and the centre: S2, L19, F10); the priors
  given to RAVE too won 16 of 20 on 9 × 9 at 400 games and 3 of 4 on 19 × 19 at 300 ms against the priors without.
- Checked: `GoSuite` — the model's moves legal to the end of whole games on 9 × 9 and 13 × 13; its thought shown; it
  passes on a won board after the opponent's pass. `scripts/e2e.scala` times its replies in Chromium on 9 × 9 and
  19 × 19.

## Online: two people far apart, and watchers (operator, 2026-11-04)

- The menu's "Play a friend online": the creator chooses a colour; the page makes the game's id and both players'
  keys (16 random bytes each, `crypto.getRandomValues`) and shows two links — the friend's (`#g=<id>&k=<their key>`,
  the other colour) and the watchers' (`#g=<id>`). The page's own address becomes the creator's link (`Place`), so a
  reload keeps the seat. A page opened by a link goes straight to that game.
- The server (`server/`, okay-serverless): a game a Durable Object (`Games.Table`, an `Actor`) named by its id, of the size its first socket says (`&size=`, 9 if none); the
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
- The links' buttons: "Copy the friend's link" (while the friend has not come, or before the first move) and "Copy
  the watchers' link" (always), by okay-ui's `Clipboard` — the browser's `navigator.clipboard` in the page
  (`InThePage.clipboard`), a list in tests (`copiedTo`). Copied: "The friend's link is copied: paste it in a
  message."; a browser that does not let the page copy: "copy … from the text" — the links stay shown as text.
- An idle game deleted: every socket opened and every action kept marks the game touched and sets its object's alarm
  for 30 days later (`Games.idle`, `Durable.wakeAt`); the alarm deletes the game when no socket is open and nothing
  touched it for 30 days, else sets itself again. A deleted game's links answer "no such game" (404). Checked in
  `GamesSuite` on `MemoryCloud` with a moved clock (`fireAlarms`).
- Where: Cloudflare (main) — https://okay-go-game.okay-effects.workers.dev, `scripts/deploy.sh`; the page's server is
  `?server=` (default that Worker). The host (the fallback, the same code): `sbt serverJVM/run` (`OKAY_GO_DIR`,
  `PORT`, `HOST`), the page served by it too when built — `http://127.0.0.1:8787/?server=ws://127.0.0.1:8787`.
- Checked: `GamesSuite` (the server on `MemoryCloud`: made by the first socket, seats, a move told to all, out of
  turn / a watcher's / illegal refused with the reason, over, a resignation kept for a later socket, keys not taken,
  a socket gone); `OnlineSuite` (two people's programs and a watcher's on one `Tables`: the links, waiting, moves
  seen, a resignation; Leave clears the address); `scripts/e2e-online.scala` — three browsers (Black, White by the
  friend's link, a watcher) to the end, a reload keeping the seat: on `wrangler dev`, on the live Worker with the
  live page, and on the host.


## A neural model (operator, 2026-11-04: "the third way" — in progress)

The plan, in order; each step checked before the next:

1. A CPU reference of a KataGo network in Scala (module `net`, JVM and Scala.js): the weights `nets/g170e-b10c128-
   s1141046784-d204142634.txt.gz` (KataGo's v1.3 release, the g170 run — CC0, katagotraining.org/network_license),
   model version 8, inputs version 7 (22 spatial, 19 global: stones, liberties, ko, five moves of history, ladders,
   pass-alive area, komi and rules). The text format, the forward pass and the inputs as written down from KataGo's
   sources (`/tmp/katago-v8-spec.md` during the work; the facts kept in `net`'s comments). Checked against KataGo
   itself (Homebrew's `katago`, `kata-raw-nn`, FP32): its outputs for a set of positions kept as a fixture, the
   reference equal to them within float rounding.
2. A search with the network (PUCT, as AlphaZero and KataGo: the policy as priors, the value at the leaves, no
   playouts) on the JVM, measured against the present MCTS on 9 × 9 and 19 × 19.
3. The network on WebGPU in the page (WGSL), equal to the CPU reference; the weights converted to a binary file at
   build time; the CPU reference where WebGPU is missing.
4. A network of our own, trained on the Mac by mlx-scala (supervised on games the search plays), in the same format.
5. okay-inlay: the legal-move mask (and liberties) as weights of the network — no illegal move without outside code.

Step 1, done: the CPU reference (`net`: `Model.parse`, `Forward`, `Inputs`, `Binary`) equals KataGo (Homebrew 1.18.2,
FP32, `kata-raw-nn 0`) on 15 positions of 9 × 9, 13 × 13 and 19 × 19 (0 to 180 moves) to 1.7e-6 (`NetSuite`; the
fixture by `Truth`). 2 990 416 parameters. On the JVM, one thread, im2col: ~53 ms an evaluation on 9 × 9, ~112 ms on
19 × 19.

Step 2, done: `Puct` (priors from the policy, the value at the leaves, c = 1.1, a first-play reduction of 0.2, a
finished game scored by the rules, the most visited move — among equals the network's own choice). With 30
evaluations a move it beat the present MCTS at 17 500 playouts (its page budget) in 8 of 8 games on 9 × 9, margins
13.5 to 44.5 (JVM, CPU).

Step 3, done: the network in the page. `GpuNet` (net/js): the weights (`Binary`, nets/b10c128.bin written by
`WriteBinary` at publishing, served beside main.js as net/b10c128.bin, 12 MB) in WebGPU buffers; the forward pass as
WGSL kernels (`Kernels`: a direct convolution a thread per output, batchnorm+ReLU, channel bias, global and value
pooling, small matmuls), a batch of positions a pass, a plan of dispatches per (batch, board size). `?selftest`
checks it against the CPU reference in the same page: the largest difference 1.3e-5 on 9 x 9, 13 x 13 and 19 x 19;
a batch of 8 in 14 / 22 / 38 ms on Apple Metal 3 (the CPU reference in Scala.js 194 / 395 / 813 ms a position).
The page's model (`NetThinking`): PUCT with leaves gathered in batches of 8 (virtual loss), for 1.5 s / 2.5 s / 4 s;
the network loaded at the first move it thinks of; without WebGPU the search that plays games out. In Chromium
~560–690 positions read a move on 9 x 9, ~410 on 19 x 19 (the CPU's inputs the limit there: BACKLOG 5b).

5c, done: the engine's information at the bottom of the game's screen, after the model's move ("The engine"): the
model (KataGo g170 b10c128: blocks, channels, weights, CC0), where it runs (WebGPU and the adapter, f16 or not; or
the CPU fallback and why), the weights (MB, load time), the GPU memory its buffers take (weights and the rest), and
the move's numbers (positions read, a second, batches) with a batch's time on the GPU, its inputs on the CPU, the
tree's. The handler says it (`Thought.about`); the program shows it.

5b, done: the CPU no longer the limit. `Scan` (core): a position scanned once on arrays — chains, liberties, the
legal points and the ko points by Zobrist hashes (the rules' superko by hashes too; equal to the game's boards on
8 838 positions, `ScanSuite`) — for the inputs, the judgement and the expansion. A pipeline: the next batch gathered
and prepared while the GPU reads one. The batch 16 on 9 x 9, 8 on larger boards. On 19 x 19 the CPU's part of a batch
from 21.5 + 14 ms to 0.6 + 3 ms; positions a move from ~430 to up to ~800 (390–800 with the GPU shared by other
work); on 9 x 9 up to ~1 150.

5d, done: the convolution tiled (`Kernels.convTiled`: an implicit matrix product, a workgroup of 16 x 16 threads a
tile of 64 channels x 64 points, 4 x 4 a thread, the sum taken 16 at a time through workgroup memory, im2col on the
fly). A batch of 8 on 19 x 19 from 38 to 11.6 ms, 13 x 13 21.6 -> 8.8, 9 x 9 14.4 -> 11.3 (its time now the dispatch
and the read back), the same results. The batch 32 on 9 x 9, 16 on the larger boards: ~3 200 positions a move on
9 x 9, ~2 600 on 19 x 19 (were ~1 150 and ~800).

6b, done: THE NETWORK ON WEBGL2 where WebGPU is missing (an iPhone XR stops at iOS 18.7: Safari without
`navigator.gpu`, and every iPhone browser is WebKit). `GlNet` (net/js), the same interface as `GpuNet` (`OnDevice`):
a tensor a float texture of four channels a texel (group g of point (x, y) of position b at (g·n + x, b·n + y)), a
layer a fragment shader drawn into the next texture — a convolution reads through the norm before it and adds a
channel bias and the residual as it writes; pooling reads through its norm; weights as 4 x 4 blocks, a texel a row.
Needs EXT_color_buffer_float. A batch runs in a task of its own (a timer's): WebGL reads back at once, and the page
must keep its turns (Menu while thinking answers after the batch in hand, ~0.35 s). The page picks WebGPU, else
WebGL2, else the search that plays games out, and says which and why ("Runs on"); `?gl` forces WebGL2. `?selftest`:
= the CPU reference within 1.7e-5 on three sizes; on Apple M4 Max (ANGLE on Metal) a batch of 8 on 19 x 19 in 20 ms
(b10c128), 10 ms (ours, 6 x 64). In Chromium, WebGL2, a batch of 8: ~450 positions a move on 9 x 9 with b10c128,
~950 with ours.
On an iPhone XR (iOS 18.7.11, Safari, A12, no WebGPU) the page picks WebGL2 by itself: `?selftest` = the CPU within
1.7e-5; a batch of 8 in 157 / 274 / 564 ms on 9 x 9 / 13 x 13 / 19 x 19 (b10c128; the CPU 407 / 901 / 1 975 ms a
position) — ~75 positions a move on 9 x 9, ~55 on 19 x 19. Faster there: our smaller network (BACKLOG 6), shaders of
eight channels a fragment, half floats (checked first).
