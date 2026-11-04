package okay.go.train

import mlx.*
import mlx.Mlx.*

/** what the network says for a batch, as MLX arrays: the policy's logits [B, n·n] and the pass's [B, 1], the value's
 * three [B, 3], the four score outputs [B, 4], ownership before tanh [B, n·n] — `Raw`'s, for a batch */
final class Out(val policy: Arr, val pass: Arr, val value: Arr, val score: Arr, val ownership: Arr)

/**
 * THE V8 NETWORK IN MLX, for a batch on an n x n board, `Forward`'s computation (a batchnorm always with its ReLU,
 * global pooling mean, mean · (√area − 14) · 0.1, max; the value head's mean, mean · (√area − 14) · 0.1,
 * mean · ((√area − 14)² · 0.01 − 0.1)) with tensors [B, n, n, C] — the teacher's and the student's, the weights the
 * list `Weights.of` makes (as arrays: what autograd differentiates).
 */
final class MlxNet(kinds: Vector[Boolean]):
  /** the inputs: spatial [B, 22, n·n] (as `Inputs` makes them, channel first), global [B, 19] */
  def apply(w: Seq[Arr], n: Int, spatial: Arr, global: Arr)(using Scope): Out =
    val b = spatial.dim(0); val area = n * n; val sq = math.sqrt(area.toDouble).toFloat
    var i = 0
    def next(): Arr = { val a = w(i); i += 1; a }
    val zero = scalar(0f)
    def conv(x: Arr): Arr = { val k = next(); conv2d(x, k, padding = (k.dim(1) - 1) / 2) }
    def normed(x: Arr): Arr = { val s = next(); val o = next(); maximum(add(multiply(x, s), o), zero) }
    def mat(x: Arr): Arr = matmul(x, next())
    def plus(t: Arr, v: Arr): Arr = add(t, reshape(v, b, 1, 1, v.dim(1)))
    def mean(x: Arr): Arr = divide(sum(reshape(x, b, area, x.dim(3)), 1), scalar(area.toFloat))
    def pool(x: Arr): Arr =
      val m = mean(x)
      concatenate(Seq(m, multiply(m, scalar((sq - 14f) * 0.1f)), max(reshape(x, b, area, x.dim(3)), 1)), 1)
    def valuePool(x: Arr): Arr =
      val m = mean(x)
      concatenate(Seq(m, multiply(m, scalar((sq - 14f) * 0.1f)),
        multiply(m, scalar((sq - 14f) * (sq - 14f) * 0.01f - 0.1f))), 1)

    val x0 = transpose(reshape(spatial, b, spatial.dim(1), n, n), 0, 2, 3, 1)
    var t = conv(x0)
    t = plus(t, mat(global))
    for pools <- kinds do
      if pools then
        val a = normed(t)
        val reg = conv(a)
        val gq = { val g = conv(a); normed(g) }
        val withPool = plus(reg, mat(pool(gq)))
        t = add(t, conv(normed(withPool)))
      else
        val r = conv(normed(t))
        t = add(t, conv(normed(r)))
    val trunk = normed(t)
    // the policy head
    val p1 = conv(trunk)
    val gc = pool(normed(conv(trunk)))
    val p1b = plus(p1, mat(gc))
    val policy = conv(normed(p1b))
    val pass = mat(gc)
    // the value head
    val v1 = normed(conv(trunk))
    val v2 = maximum(add(mat(valuePool(v1)), next()), zero)
    val value = add(mat(v2), next())
    val score = add(mat(v2), next())
    val own = conv(v1)
    require(i == w.size, s"${w.size - i} weights not used")
    Out(reshape(policy, b, area), pass, value, score, reshape(own, b, area))

object MlxNet:
  /** the tensors as MLX arrays, in `scope` */
  def arrays(ts: Vector[Tensor])(using Scope): Vector[Arr] = ts.map(t => floats(t.data, t.shape*))
