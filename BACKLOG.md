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
5c. [x] (operator, 2026-11-04) The engine's information at the bottom of the game's screen — which model runs
   (KataGo g170 b10c128: blocks, channels, weights, licence), whether on WebGPU (the adapter, f16) or the CPU
   fallback, the weights' size and load time, the GPU memory the buffers take, and for the last move: positions read,
   positions a second, the time of a batch on the GPU and of its inputs on the CPU, the search's settings.
5b. [x] The inputs' speed in the page — on 19 x 19 the CPU's inputs (ladders, Benson's area: Scala collections)
   cost more than the GPU's pass (~410 positions a move where the GPU would read ~850): arrays, the area and ladders
   kept between a node and its children where they can be; measured in Chromium.
   Done: one `Scan` a leaf (chains, liberties, legal points and ko by Zobrist hashes, equal to the boards on 8 838
   positions) for the inputs, the judgement and the expansion; the rules' superko by hashes too; a pipeline (the
   next batch prepared while the GPU reads one); the batch 16 on 9 x 9, 8 on larger boards. 19 x 19: the CPU's part
   of a batch 21.5 + 14 ms -> 0.6 + 3 ms; ~430 -> up to ~800 positions a move (the GPU shared with other work on
   this Mac: 390–800); 9 x 9 up to ~1 150. The GPU is now the limit.
5d. [x] The GPU kernels faster on 19 x 19 — the convolution tiled in workgroup memory (inputs and weights
   shared by a workgroup), maybe f16 where the adapter has it (checked against the reference); measured by
   `?selftest`.
   Done: the convolution tiled (64 channels x 64 points a workgroup of 16 x 16, 4 x 4 a thread, slices of 16 in
   workgroup memory, im2col on the fly): a batch of 8 on 19 x 19 38 -> 11.6 ms, the same results (1.3e-5 from the
   reference); the batch 32 on 9 x 9 and 16 on larger boards: ~3 200 positions a move on 9 x 9, ~2 600 on 19 x 19
   (were ~1 150 and ~800). f16 not needed for now.
6. [ ] Step 4: a network of our own, trained on the Mac by mlx-scala (supervised on games the search plays), in the
   same format; measured against KataGo's.
7. [ ] Step 5: okay-inlay — the legal-move mask (and liberties) as weights of the network: no illegal move without
   outside code (Wordle's step 5 in Go).
   (user, 2026-11-04) Before 7: 6b. [x] done 2026-11-04 (specs, "6b"). THE NETWORK ON WEBGL2 where WebGPU is missing — an iPhone XR stops at iOS 18.7, whose
   Safari has no WebGPU (`navigator.gpu` missing; every iPhone browser is WebKit), but has WebGL2 with float render
   targets: the same layers as fragment shaders over float textures (a conv as a sum over its taps), for our own
   smaller network first (6 x 64: few enough for an old phone); checked = `Forward` by `?selftest`; the page
   chooses WebGPU, else WebGL2, else the search that plays games out, and the engine panel says which.
   (user, 2026-11-04) 6c. [ ] EVERY GAME KEPT AND CONTINUED ANYWHERE: a game against the model and two players at one
   screen are made on the games' server as online ones are; the page holds the other seat's key too (the link
   #g=…&k=…&m=… — the model's seat, thought in this page — or &o=… — the other person's at this screen) and sends
   that seat's moves with its key (an action may carry a key: the server seats it by the key). "Continue on another
   device": the link as a QR code and a copy button. "Continue a game" in the menu: the games of this browser
   (okay-ui's `Settings` in localStorage), most recent first, a finished one dropped. Server change backward
   compatible; checked by OnlineSuite (memory), GamesSuite (the host), e2e-online on wrangler dev, then deployed.
