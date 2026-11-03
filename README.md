# okay-go-game

The game of Go on a 9 × 9 board, in a page, written on [okay-ui](https://github.com/okay-effects/okay-ui) — business
screens as data on algebraic effects — and drawn by [okay-faces](https://github.com/okay-effects/okay-faces)' Laminar
face. Play: https://okay-effects.github.io/okay-go-game/

```sh
sbt appJS/web          # links the page into app/.js/target/web
open app/.js/target/web/index.html
```

Two players at one board, or a person against a toy model (twelve numbers). Only legal points can be pressed — a
taken point, a suicide or a ko has no button — and the model's wishes go through the same mask: the page shows what
it wanted and what it was allowed to play. Area scoring, komi 6.5.
