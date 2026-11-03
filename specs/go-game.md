# okay-go-game — the game of Go in a page, on okay-ui and okay-faces (spec, 2026-11-04)

(Not `okay-go`: that name is the okay-effects library for the Go language.)

## The rule

The data model is effects; every implementation is a handler; the browser runs the application in ONE line.

| effect | operation | answered with | handlers |
|---|---|---|---|
| `Model` | `Logits(position)` | a logit for each of the 81 points, then one for passing | `Model.toy()` (the page, tests with a seed) |
| `Ui` | `Show(screen)` | the button pressed | okay-ui's `steps` |

```scala
Page.mount(OnFaces.program(steps(Model.toy()(Go.main[App]))), skin)
```

## The rules (`Rules`)

9 × 9; Black begins. A group with no liberty is captured; a stone whose group would have no liberty (capturing
nothing) is suicide and not allowed; no move may bring back a board seen before (positional superko — it covers ko).
A pass instead of a stone; two passes in a row end the game. The score by area (Tromp-Taylor): stones plus the empty
points whose region reaches only that colour; komi 6.5 to White, so no draw. Dead stones are not marked: they are
captured before passing, the board counted as it stands. Points named by column (A–J, no I) and row from the bottom.

## The game (`Go`)

- The menu: Two players, Play Black against the model, Play White against the model, Rules. No Quit.
- The board (`Board`): `Part.block("go")` of a picture — the wood, 9 lines each way, the star points, the stones, the
  last move's dot, at the end each empty point's owner — and over it `go-points-<colour to play>`, a grid of 81
  blocks with a button at each LEGAL point only (named by the point, E5): a taken point, a suicide or a ko cannot be
  asked for. The skin draws the button as a round shadow of the stone to be played under the pointer.
- A turn: the status (who plays, the move, captures), Pass, Resign, Menu. Against the model, after the person's move
  the model moves at once: its logits; what it WANTED (the highest of all 82) and what it PLAYED (the highest legal
  point, or a pass) — shown with the reason when they differ ("D5 — not allowed: the point is taken").
- The end: two passes — the result, Black's and White's counts; or a resignation.

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
