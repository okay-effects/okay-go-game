package okay.go.net

/** THE FORWARD PASS'S KERNELS IN WGSL, a batch of positions at once, tensors [batch][channel][point] of f32, the
 * grid the board's own size (so no mask): one thread an output value, 64 to a workgroup */
object Kernels:
  private def index =
    """let i = id.x + id.y * 65535u * 64u;"""

  /** out[b][oc][p] (=, or += when acc) sum over (dy, dx, ic) of w[((dy*kx+dx)*cin+ic)*cout+oc] * in[b][ic][p'] */
  val conv: String = s"""
struct P { batch: u32, n: u32, cin: u32, cout: u32, ky: u32, kx: u32, acc: u32, pad: u32 }
@group(0) @binding(0) var<uniform> p: P;
@group(0) @binding(1) var<storage, read> inp: array<f32>;
@group(0) @binding(2) var<storage, read> w: array<f32>;
@group(0) @binding(3) var<storage, read_write> outp: array<f32>;
@compute @workgroup_size(64)
fn main(@builtin(global_invocation_id) id: vec3<u32>) {
  $index
  let a = p.n * p.n;
  if (i >= p.batch * p.cout * a) { return; }
  let b = i / (p.cout * a); let r = i % (p.cout * a);
  let oc = r / a; let pt = r % a;
  let y = i32(pt / p.n); let x = i32(pt % p.n);
  let py = i32((p.ky - 1u) / 2u); let px = i32((p.kx - 1u) / 2u);
  var s = 0.0;
  for (var dy = 0u; dy < p.ky; dy = dy + 1u) {
    let sy = y + i32(dy) - py;
    if (sy < 0 || sy >= i32(p.n)) { continue; }
    for (var dx = 0u; dx < p.kx; dx = dx + 1u) {
      let sx = x + i32(dx) - px;
      if (sx < 0 || sx >= i32(p.n)) { continue; }
      let wb = (dy * p.kx + dx) * p.cin;
      let ib = b * p.cin * a + u32(sy) * p.n + u32(sx);
      for (var ic = 0u; ic < p.cin; ic = ic + 1u) {
        s = s + w[(wb + ic) * p.cout + oc] * inp[ib + ic * a];
      }
    }
  }
  let o = (b * p.cout + oc) * a + pt;
  if (p.acc == 1u) { outp[o] = outp[o] + s; } else { outp[o] = s; }
}"""

  /** THE CONVOLUTION TILED, as an implicit matrix product: a workgroup of 16 x 16 threads makes a tile of 64 output
   * channels by 64 points of one position (workgroup z), each thread 4 x 4 of them (channels ty + 16i, points
   * tx + 16j); the sum over k = (dy * kx + dx) * cin + ic (the weights' own order) taken 16 at a time — a slice of the
   * weights [16][64] and of the unrolled input [16][64] (im2col on the fly, 0 beyond the edge) put in workgroup
   * memory, then 16 x 16 products a thread. Dispatched (points / 64, channels / 64, batch). */
  val convTiled: String = """
struct P { batch: u32, n: u32, cin: u32, cout: u32, ky: u32, kx: u32, acc: u32, pad: u32 }
@group(0) @binding(0) var<uniform> p: P;
@group(0) @binding(1) var<storage, read> inp: array<f32>;
@group(0) @binding(2) var<storage, read> w: array<f32>;
@group(0) @binding(3) var<storage, read_write> outp: array<f32>;
var<workgroup> ws: array<f32, 1024>;
var<workgroup> xs: array<f32, 1024>;
@compute @workgroup_size(16, 16)
fn main(@builtin(workgroup_id) wg: vec3<u32>, @builtin(local_invocation_id) lid: vec3<u32>) {
  let a = p.n * p.n;
  let b = wg.z; let p0 = wg.x * 64u; let o0 = wg.y * 64u;
  let kAll = p.ky * p.kx * p.cin;
  let tid = lid.y * 16u + lid.x;
  let py = i32((p.ky - 1u) / 2u); let px = i32((p.kx - 1u) / 2u);
  var acc: array<f32, 16>;
  for (var k0 = 0u; k0 < kAll; k0 = k0 + 16u) {
    for (var r = 0u; r < 4u; r = r + 1u) {
      let e = tid + r * 256u;
      let kk = e / 64u; let col = e % 64u;
      let k = k0 + kk;
      var wv = 0.0;
      if (k < kAll && o0 + col < p.cout) { wv = w[k * p.cout + o0 + col]; }
      ws[e] = wv;
      var xv = 0.0;
      let pt = p0 + col;
      if (k < kAll && pt < a) {
        let ic = k % p.cin; let t = k / p.cin; let dx = t % p.kx; let dy = t / p.kx;
        let y = i32(pt / p.n) + i32(dy) - py; let x = i32(pt % p.n) + i32(dx) - px;
        if (y >= 0 && y < i32(p.n) && x >= 0 && x < i32(p.n)) {
          xv = inp[(b * p.cin + ic) * a + u32(y) * p.n + u32(x)];
        }
      }
      xs[e] = xv;
    }
    workgroupBarrier();
    for (var kk = 0u; kk < 16u; kk = kk + 1u) {
      for (var i = 0u; i < 4u; i = i + 1u) {
        let wv = ws[kk * 64u + lid.y + 16u * i];
        for (var j = 0u; j < 4u; j = j + 1u) {
          acc[i * 4u + j] = acc[i * 4u + j] + wv * xs[kk * 64u + lid.x + 16u * j];
        }
      }
    }
    workgroupBarrier();
  }
  for (var i = 0u; i < 4u; i = i + 1u) {
    let oc = o0 + lid.y + 16u * i;
    for (var j = 0u; j < 4u; j = j + 1u) {
      let pt = p0 + lid.x + 16u * j;
      if (oc < p.cout && pt < a) {
        let o = (b * p.cout + oc) * a + pt;
        if (p.acc == 1u) { outp[o] = outp[o] + acc[i * 4u + j]; } else { outp[o] = acc[i * 4u + j]; }
      }
    }
  }
}"""

  /** out = relu(in * scale[c] + bias[c]) */
  val norm: String = s"""
struct P { batch: u32, c: u32, a: u32, pad: u32 }
@group(0) @binding(0) var<uniform> p: P;
@group(0) @binding(1) var<storage, read> inp: array<f32>;
@group(0) @binding(2) var<storage, read> scale: array<f32>;
@group(0) @binding(3) var<storage, read> bias: array<f32>;
@group(0) @binding(4) var<storage, read_write> outp: array<f32>;
@compute @workgroup_size(64)
fn main(@builtin(global_invocation_id) id: vec3<u32>) {
  $index
  if (i >= p.batch * p.c * p.a) { return; }
  let c = (i / p.a) % p.c;
  outp[i] = max(0.0, inp[i] * scale[c] + bias[c]);
}"""

  /** t[b][c][p] += bias[b][c] */
  val channelBias: String = s"""
struct P { batch: u32, c: u32, a: u32, pad: u32 }
@group(0) @binding(0) var<uniform> p: P;
@group(0) @binding(1) var<storage, read> bias: array<f32>;
@group(0) @binding(2) var<storage, read_write> t: array<f32>;
@compute @workgroup_size(64)
fn main(@builtin(global_invocation_id) id: vec3<u32>) {
  $index
  if (i >= p.batch * p.c * p.a) { return; }
  t[i] = t[i] + bias[i / p.a];
}"""

  /** global pooling, one thread a (b, c): mean, mean * (sqrt(a) - 14) * 0.1, then max — or for the value head
   * (value = 1) mean * ((s - 14)^2 * 0.01 - 0.1) in the max's place */
  val pool: String = s"""
struct P { batch: u32, c: u32, a: u32, value: u32 }
@group(0) @binding(0) var<uniform> p: P;
@group(0) @binding(1) var<storage, read> inp: array<f32>;
@group(0) @binding(2) var<storage, read_write> outp: array<f32>;
@compute @workgroup_size(64)
fn main(@builtin(global_invocation_id) id: vec3<u32>) {
  $index
  if (i >= p.batch * p.c) { return; }
  let b = i / p.c; let c = i % p.c;
  var s = 0.0; var m = -1.0;
  for (var k = 0u; k < p.a; k = k + 1u) { let v = inp[i * p.a + k]; s = s + v; m = max(m, v); }
  let mean = s / f32(p.a);
  let q = sqrt(f32(p.a)) - 14.0;
  let o = b * 3u * p.c;
  outp[o + c] = mean;
  outp[o + p.c + c] = mean * q * 0.1;
  if (p.value == 1u) { outp[o + 2u * p.c + c] = mean * (q * q * 0.01 - 0.1); } else { outp[o + 2u * p.c + c] = m; }
}"""

  /** out[b][oc] = sum over ic of in[b][ic] * w[ic][oc] (+ bias[oc] when hasBias; relu when relu) */
  val matmul: String = s"""
struct P { batch: u32, cin: u32, cout: u32, flags: u32 }
@group(0) @binding(0) var<uniform> p: P;
@group(0) @binding(1) var<storage, read> inp: array<f32>;
@group(0) @binding(2) var<storage, read> w: array<f32>;
@group(0) @binding(3) var<storage, read> bias: array<f32>;
@group(0) @binding(4) var<storage, read_write> outp: array<f32>;
@compute @workgroup_size(64)
fn main(@builtin(global_invocation_id) id: vec3<u32>) {
  $index
  if (i >= p.batch * p.cout) { return; }
  let b = i / p.cout; let oc = i % p.cout;
  var s = 0.0;
  for (var ic = 0u; ic < p.cin; ic = ic + 1u) { s = s + inp[b * p.cin + ic] * w[ic * p.cout + oc]; }
  if ((p.flags & 1u) == 1u) { s = s + bias[oc]; }
  if ((p.flags & 2u) == 2u) { s = max(0.0, s); }
  outp[i] = s;
}"""
