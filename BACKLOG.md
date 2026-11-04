# BACKLOG — okay-go-game

In the operator's order (2026-11-04: "write everything in the backlog and do it in order"). Done items stay, marked.

1. [x] The page's foot as Coinpath's — "Built on okay-effects — algebraic effects for Scala", "Need more?" to
   okay-effects.github.io/#author (also Wordle and Sudoku; the rule kept for every new app).
2. [x] okay-reliability among the live apps on okay-effects.github.io (another session had added it already).
3. [x] Step 1 of the neural model: a KataGo network's CPU reference (module `net`) equal to KataGo itself
   (specs/go-game.md, "A neural model").
4. [x] Step 2: the search with the network (PUCT) — 30 evaluations a move beat the present MCTS (17 500 playouts, its
   page budget) 8 of 8 on 9 × 9 (margins 13.5–44.5), JVM, CPU.
5. [x] Step 3: the KataGo network in the page — weights as a binary file (`Binary`), the forward pass on WebGPU
   (WGSL), equal to the CPU reference in Chromium; PUCT with batched leaves; the CPU reference (Scala.js) where
   WebGPU is missing; the model of the page the network (Go's `Model` handled by the network's search), its budget a
   time per move like the present one; e2e in Chromium; published.
   Done 2026-11-04: WebGPU equal to the CPU reference to 1.3e-5 (`?selftest`); a batch of 8 in 14 ms (9 x 9), 38 ms
   (19 x 19) on Apple Metal 3; in play ~560–690 positions read a move on 9 x 9 in 1.5 s, ~410 on 19 x 19 in 4 s.
5b. [ ] NOW: the inputs' speed in the page — on 19 x 19 the CPU's inputs (ladders, Benson's area: Scala collections)
   cost more than the GPU's pass (~410 positions a move where the GPU would read ~850): arrays, the area and ladders
   kept between a node and its children where they can be; measured in Chromium.
6. [ ] Step 4: a network of our own, trained on the Mac by mlx-scala (supervised on games the search plays), in the
   same format; measured against KataGo's.
7. [ ] Step 5: okay-inlay — the legal-move mask (and liberties) as weights of the network: no illegal move without
   outside code (Wordle's step 5 in Go).
