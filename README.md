# okay-go-game

The game of Go in a page, written on [okay-ui](https://github.com/okay-effects/okay-ui) — business
screens as data on algebraic effects — and drawn by [okay-faces](https://github.com/okay-effects/okay-faces)' Laminar
face. Play: https://okay-effects.github.io/okay-go-game/

```sh
sbt appJS/web          # links the page into app/.js/target/web
open app/.js/target/web/index.html
```

On 9 × 9, 13 × 13 or 19 × 19: two players at one board, two people far apart by a link (with watchers by another),
or a person against a model that plays out thousands of games before each move (Monte-Carlo tree search with RAVE,
heavy playouts and prior knowledge). Games far apart live on a Cloudflare Worker, each game a Durable Object (`server/`,
okay-serverless; the same server runs on its JVM host as the fallback). Only legal points can be pressed — a
taken point, a suicide or a ko has no button. While the model thinks, the page stays alive (it thinks between the
page's frames) and then says its chance to win. Area scoring, komi 6.5 on 9 × 9 and 7.5 on the larger boards.
