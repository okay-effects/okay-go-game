# SPRINT — okay-go-game

## In flight: BACKLOG 6 — a network of our own, trained by mlx-scala (started 2026-11-04)

The plan (written before the code; tick as it lands):

- [x] mlx-scala: `conv2d`, `max`, `maximum` (okm.c, FFM, Native; tested on both; 0.1.0 republished).
- [x] `train/` (JVM only, sbt project): the v8 network in MLX (`Mlx.conv2d`, NHWC) — ONE code for the teacher and
  the student. Checked: KataGo's b10c128 in MLX = `Forward` (the CPU reference) on `net/jvm/src/test/resources`.
- [ ] Online distillation, nothing on disk but the result: the teacher (b10c128 in MLX) plays B games at once on
  9 x 9 (and 13, 19), moves sampled from its policy (temperature, a few random openings); each position's inputs
  (`Inputs.of`) and the teacher's outputs (policy with pass, value, score, ownership) go into a replay buffer in
  memory; the student learns from random batches of it: cross-entropy to the teacher's policy and value, small
  weights on ownership and score. The student: v8's layout (blocks with global pooling as KataGo's), no batch
  statistics — a norm is a learned scale and bias (KataGo's form, mean 0, variance 1), the second conv of each block
  zero at the start (the block the identity).
- [ ] The student written by `Binary` to `nets/own.bin`: `GpuNet` runs it unchanged. Checked: MLX = `Forward` on it.
- [ ] Measured: policy agreement with the teacher on held-out positions; games in `PuctSuite`'s way (CPU, the same
  evaluations) and at the same time on the GPU (more positions read by a smaller net); the page's speed.
- [ ] If it is worth it: the page offers it (the engine panel says which network).

How to resume: `sbt "train/runMain okay.go.train.Distill"` (see its doc); the log in `train/target/distill.log`.

Notes as it runs (2026-11-04):
- The run: `nohup sbt -batch "train/runMain okay.go.train.Distill 100000" > /tmp/distill-run.out &` (sbt is in
  ~/Library/Application Support/Coursier/bin — not on a fresh shell's PATH). ~1 000 steps a minute alone.
- ONE MLX job on the GPU at a time: a `Match` beside the run stalled it 11 minutes (no hang: it went on when the
  match ended). Matches after the run.
- At step 10 000 (agree 53 % on 9 x 9): 1 of 32 against b10c128 at 100 visits each — early; measured again at the end.
- In the page: `?net=own` runs it on WebGPU unchanged (= CPU within 2.4e-6); a batch of 8 on 19 x 19 7.4 ms against
  b10c128's 16.2 ms (the GPU shared), the CPU 6 x faster.
- DONE 2026-11-04 16:41 (resumed at 42 000 and 43 000 after a reboot and the memory cap): step 100 000, 2.2 M positions
  of 22 663 teacher games; held out, agree with b10c128's argmax 61.3 % (9 x 9), 56.5 % (13), 56.8 % (19). nets/own.bin
  1.9 MB (b10c128 12 MB); saved copies nets/own-42000.bin, own-43000.bin, own-100000.bin.
- Matches on 9 x 9 (32 games, colours swapped): ours at 100 visits against b10c128 at 100: 4 won (13 %); ours at 400
  (its speed: ~3-4 x) against b10c128 at 100: 11 won (34 %). The teacher stays the page's default; ours at ?net=own,
  published. WebKit's WebGL2 on the Mac, a batch of 8: ours 7 / 11 / 7 ms, b10c128 20 / 15 / 21 ms.
- Next, if ours is to beat the teacher at equal time: a larger student (6 x 96 or 10 x 64), targets from the teacher's
  search (PUCT visits) rather than its raw policy, longer. First: the XR's numbers for ?selftest&net=own.

## In flight: BACKLOG 6, round 2 — a larger student (started 2026-11-04 ~17:40)

- [ ] 6 x 96 (pooling channels a quarter: 24), from scratch, 100 000 steps, to nets/own-6x96.bin (`--out=`);
  `sbt "train/runMain okay.go.train.Distill 100000 6 96 --out=nets/own-6x96.bin"`, the log train/target/distill.log
  (resume: `--resume=<step>` with the same --out). ~2 x the 6 x 64's time a step.
- [ ] Measured as round 1: agreement; Match at 100/100 and at its speed's visits; WebKit's selftest.
- [ ] Better than round 1 at equal time → the page's ?net=own; better than b10c128 at equal time → the default.
- Then, if capacity is not the limit: targets from the teacher's search (PUCT visits on a quarter of the moves,
  KataGo's playout cap randomisation), round 3.
