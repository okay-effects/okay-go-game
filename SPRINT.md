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
