# okay-go-game

The game of Go on a 9 × 9 board, in a page, written on [okay-ui](https://github.com/okay-effects/okay-ui) — business
screens as data on algebraic effects — and drawn by [okay-faces](https://github.com/okay-effects/okay-faces)' Laminar
face. Play: https://okay-effects.github.io/okay-go-game/

```sh
sbt appJS/web          # links the page into app/.js/target/web
open app/.js/target/web/index.html
```

Two players at one board, two people far apart by a link (with watchers by another), or a person against a model: a
Beginner of twelve numbers, or a Stronger one that plays thousands of games out before each move (Monte-Carlo tree
search). Games far apart live on a Cloudflare Worker, each game a Durable Object (`server/`,
okay-serverless; the same server runs on its JVM host as the fallback). Only legal points can be pressed — a
taken point, a suicide or a ko has no button — and the model's wishes go through the same mask: the page shows what
it wanted and what it was allowed to play. Area scoring, komi 6.5.
