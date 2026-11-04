# SPRINT — okay-go-game

In flight: BACKLOG 5 — the KataGo network in the page on WebGPU.

- the weights converted at build time (`Binary.write`) into app/web/net/b10c128.bin, served beside main.js;
- WGSL kernels for the forward pass of model version 8 (conv as im2col-free direct convolution per (channel, point),
  batchnorm+ReLU+mask, residual add, channel bias, global pooling, value pooling, small matmuls), a batch of
  positions at once; the inputs computed on the CPU (`Inputs`);
- checked: the WebGPU outputs equal the CPU reference (`Forward`) for the fixture's positions, in Chromium
  (headless with WebGPU enabled), within 1e-4;
- PUCT batched (virtual loss) so a batch of leaves is one dispatch; the page's model the network's search; the
  CPU reference in Scala.js when `navigator.gpu` is missing.
