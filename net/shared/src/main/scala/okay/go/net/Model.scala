package okay.go.net

/**
 * A KATAGO NETWORK OF MODEL VERSION 8 (the g170 run's), its layers as arrays of floats. The text format, as KataGo's
 * `desc.cpp` reads it: whitespace-separated tokens; a conv `name ky kx in out dy dx` then weights in the order y, x,
 * in, out (out fastest); a batchnorm `name C epsilon hasScale hasBias` then mean, variance, scale (if any), bias (if
 * any), merged at load into `scale / sqrt(variance + epsilon)` and `bias - mergedScale * mean`; an activation its
 * name alone (ReLU before version 11); a matmul `name in out` then weights in, out (out fastest); a matbias
 * `name C` then C floats. Subnormal floats are flushed to 0.
 */
final class Conv(val name: String, val ky: Int, val kx: Int, val in: Int, val out: Int, val w: Array[Float])
final class Norm(val name: String, val channels: Int, val scale: Array[Float], val bias: Array[Float])
final class MatMul(val name: String, val in: Int, val out: Int, val w: Array[Float])
final class MatBias(val name: String, val channels: Int, val b: Array[Float])

sealed trait Block
final case class Ordinary(norm1: Norm, w1: Conv, norm2: Norm, w2: Conv) extends Block
final case class Pooling(norm1: Norm, w1a: Conv, w1b: Conv, norm1b: Norm, w1r: MatMul, norm2: Norm, w2: Conv)
    extends Block

final class Model(val name: String, val version: Int, val spatial: Int, val global: Int,
    val conv1: Conv, val ginput: MatMul, val blocks: Vector[Block], val trunkNorm: Norm,
    // the policy head
    val p1: Conv, val g1: Conv, val g1Norm: Norm, val g2: MatMul, val p1Norm: Norm, val p2: Conv, val pass: MatMul,
    // the value head
    val v1: Conv, val v1Norm: Norm, val v2: MatMul, val v2b: MatBias, val v3: MatMul, val v3b: MatBias,
    val sv3: MatMul, val sv3b: MatBias, val own: Conv):
  def parameters: Long =
    def c(x: Conv) = x.w.length.toLong
    def m(x: MatMul) = x.w.length.toLong
    val bs = blocks.map {
      case Ordinary(_, w1, _, w2) => c(w1) + c(w2)
      case Pooling(_, a, b, _, r, _, w2) => c(a) + c(b) + m(r) + c(w2)
    }.sum
    c(conv1) + m(ginput) + bs + c(p1) + c(g1) + m(g2) + c(p2) + m(pass) + c(v1) + m(v2) + m(v3) + m(sv3) + c(own)

object Model:
  /** the text of a model, read */
  def parse(text: String): Model =
    val t = Tokens(text)
    val name = t.word(); val version = t.int(); val spatial = t.int(); val global = t.int()
    require(version == 8, s"model version $version: only 8 is read here")
    t.expect("trunk")
    val numBlocks = t.int(); val trunkC = t.int(); t.int(); t.int(); t.int(); t.int() // mid, regular, dilated, gpool
    val conv1 = t.conv(); val ginput = t.matmul()
    val blocks = Vector.fill(numBlocks) {
      t.word() match
        case "ordinary_block" =>
          t.word(); val n1 = t.norm(); t.word(); val w1 = t.conv(); val n2 = t.norm(); t.word(); val w2 = t.conv()
          Ordinary(n1, w1, n2, w2)
        case "gpool_block" =>
          t.word(); val n1 = t.norm(); t.word(); val a = t.conv(); val b = t.conv(); val nb = t.norm(); t.word()
          val r = t.matmul(); val n2 = t.norm(); t.word(); val w2 = t.conv()
          Pooling(n1, a, b, nb, r, n2, w2)
        case other => throw IllegalArgumentException(s"a block of kind $other")
    }
    val trunkNorm = t.norm(); t.word()
    t.expect("policyhead")
    val p1 = t.conv(); val g1 = t.conv(); val g1Norm = t.norm(); t.word(); val g2 = t.matmul(); val p1Norm = t.norm()
    t.word(); val p2 = t.conv(); val pass = t.matmul()
    t.expect("valuehead")
    val v1 = t.conv(); val v1Norm = t.norm(); t.word(); val v2 = t.matmul(); val v2b = t.matbias(); t.word()
    val v3 = t.matmul(); val v3b = t.matbias(); val sv3 = t.matmul(); val sv3b = t.matbias(); val own = t.conv()
    require(trunkC == conv1.out)
    Model(name, version, spatial, global, conv1, ginput, blocks, trunkNorm, p1, g1, g1Norm, g2, p1Norm, p2, pass,
      v1, v1Norm, v2, v2b, v3, v3b, sv3, sv3b, own)

  /** the tokens of the text, one after another */
  private final class Tokens(s: String):
    private var i = 0
    private def skip(): Unit = while i < s.length && s.charAt(i).isWhitespace do i += 1
    def word(): String =
      skip(); val from = i
      while i < s.length && !s.charAt(i).isWhitespace do i += 1
      s.substring(from, i)
    def int(): Int = word().toInt
    def float(): Float =
      val f = word().toFloat
      require(!f.isNaN && !f.isInfinite, "a weight not finite")
      if math.abs(f) < java.lang.Float.MIN_NORMAL then 0f else f
    def floats(n: Int): Array[Float] = Array.fill(n)(float())
    def expect(w: String): Unit = { val got = word(); require(got == w, s"$w expected, $got found") }
    def conv(): Conv =
      val name = word(); val ky = int(); val kx = int(); val in = int(); val out = int(); int(); int()
      Conv(name, ky, kx, in, out, floats(ky * kx * in * out))
    def norm(): Norm =
      val name = word(); val c = int(); val eps = word().toDouble; val hasScale = int() == 1; val hasBias = int() == 1
      val mean = floats(c); val variance = floats(c)
      val scale = if hasScale then floats(c) else Array.fill(c)(1f)
      val bias = if hasBias then floats(c) else Array.fill(c)(0f)
      val merged = Array.tabulate(c)(k => (scale(k) / math.sqrt(variance(k) + eps)).toFloat)
      Norm(name, c, merged, Array.tabulate(c)(k => bias(k) - merged(k) * mean(k)))
    def matmul(): MatMul =
      val name = word(); val in = int(); val out = int()
      MatMul(name, in, out, floats(in * out))
    def matbias(): MatBias =
      val name = word(); val c = int()
      MatBias(name, c, floats(c))
