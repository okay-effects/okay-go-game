package okay.go.train

import okay.go.net.*

/** A tensor of the network: its floats in MLX's layout and its shape */
final class Tensor(val data: Array[Float], val shape: Vector[Int])

/**
 * A V8 NETWORK'S WEIGHTS AS A LIST, in `Binary`'s order — the list autograd differentiates: a conv [out, ky, kx, in]
 * (MLX's layout; KataGo's is [ky, kx, in, out]), a norm its scale then its bias [C] (merged: KataGo's batchnorm as
 * the net computes it), a matmul [in, out], a matbias [C]. `kinds` says which blocks pool (true) and which do not.
 */
object Weights:
  def of(m: Model): Vector[Tensor] =
    val out = Vector.newBuilder[Tensor]
    def conv(c: Conv): Unit =
      val w = new Array[Float](c.w.length)
      for y <- 0 until c.ky; x <- 0 until c.kx; i <- 0 until c.in; o <- 0 until c.out do
        w(((o * c.ky + y) * c.kx + x) * c.in + i) = c.w(((y * c.kx + x) * c.in + i) * c.out + o)
      out += Tensor(w, Vector(c.out, c.ky, c.kx, c.in))
    def norm(b: Norm): Unit = { out += Tensor(b.scale, Vector(b.channels)); out += Tensor(b.bias, Vector(b.channels)) }
    def mat(x: MatMul): Unit = out += Tensor(x.w, Vector(x.in, x.out))
    def bias(x: MatBias): Unit = out += Tensor(x.b, Vector(x.channels))
    conv(m.conv1); mat(m.ginput)
    m.blocks.foreach {
      case Ordinary(n1, w1, n2, w2) => norm(n1); conv(w1); norm(n2); conv(w2)
      case Pooling(n1, a, b, nb, r, n2, w2) => norm(n1); conv(a); conv(b); norm(nb); mat(r); norm(n2); conv(w2)
    }
    norm(m.trunkNorm)
    conv(m.p1); conv(m.g1); norm(m.g1Norm); mat(m.g2); norm(m.p1Norm); conv(m.p2); mat(m.pass)
    conv(m.v1); norm(m.v1Norm); mat(m.v2); bias(m.v2b); mat(m.v3); bias(m.v3b); mat(m.sv3); bias(m.sv3b); conv(m.own)
    out.result()

  def kinds(m: Model): Vector[Boolean] = m.blocks.map(_.isInstanceOf[Pooling])

  /** the list back as a model (names made up: `Binary` keeps none) */
  def model(name: String, kinds: Vector[Boolean], ts: Vector[Tensor]): Model =
    val it = ts.iterator
    def conv(): Conv =
      val t = it.next(); val Vector(o, ky, kx, in) = t.shape: @unchecked
      val w = new Array[Float](t.data.length)
      for y <- 0 until ky; x <- 0 until kx; i <- 0 until in; oc <- 0 until o do
        w(((y * kx + x) * in + i) * o + oc) = t.data(((oc * ky + y) * kx + x) * in + i)
      Conv("conv", ky, kx, in, o, w)
    def norm(): Norm = { val s = it.next(); val b = it.next(); Norm("norm", s.shape(0), s.data, b.data) }
    def mat(): MatMul = { val t = it.next(); MatMul("matmul", t.shape(0), t.shape(1), t.data) }
    def bias(): MatBias = { val t = it.next(); MatBias("bias", t.shape(0), t.data) }
    val conv1 = conv(); val ginput = mat()
    val blocks = kinds.map { pools =>
      if pools then
        val n1 = norm(); val a = conv(); val b = conv(); val nb = norm(); val r = mat(); val n2 = norm()
        Pooling(n1, a, b, nb, r, n2, conv())
      else
        val n1 = norm(); val w1 = conv(); val n2 = norm(); Ordinary(n1, w1, n2, conv())
    }
    val trunkNorm = norm()
    val p1 = conv(); val g1 = conv(); val g1Norm = norm(); val g2 = mat(); val p1Norm = norm(); val p2 = conv()
    val pass = mat()
    val v1 = conv(); val v1Norm = norm(); val v2 = mat(); val v2b = bias(); val v3 = mat(); val v3b = bias()
    val sv3 = mat(); val sv3b = bias(); val own = conv()
    require(!it.hasNext, "tensors left over")
    Model(name, 8, conv1.in, ginput.in, conv1, ginput, blocks, trunkNorm, p1, g1, g1Norm, g2, p1Norm, p2, pass,
      v1, v1Norm, v2, v2b, v3, v3b, sv3, sv3b, own)

  /**
   * A NEW STUDENT, as KataGo's layout at a smaller size: `channels` in the trunk, of them `pooled` through global
   * pooling in a pooling block, `head` channels in each head. Initialised for a network without batch statistics
   * (Fixup's idea): convolutions He-normal, a norm the identity (scale 1, bias 0), the LAST conv of every block
   * zero — each block the identity at the start, the trunk as deep as it learns to be.
   */
  def student(kinds: Vector[Boolean], channels: Int, pooled: Int, head: Int, valueHidden: Int,
      random: scala.util.Random): Vector[Tensor] =
    def gauss(n: Int, std: Double) = Array.fill(n)((random.nextGaussian() * std).toFloat)
    def conv(k: Int, in: Int, out: Int, zero: Boolean = false, std: Double = -1) =
      val s = if std >= 0 then std else math.sqrt(2.0 / (k * k * in))
      Tensor(if zero then new Array[Float](out * k * k * in) else gauss(out * k * k * in, s), Vector(out, k, k, in))
    def norm(c: Int) = Vector(Tensor(Array.fill(c)(1f), Vector(c)), Tensor(new Array[Float](c), Vector(c)))
    def mat(in: Int, out: Int, std: Double) = Tensor(gauss(in * out, std), Vector(in, out))
    def bias(c: Int) = Tensor(new Array[Float](c), Vector(c))
    val c = channels; val reg = c - pooled
    val trunk = Vector(conv(5, 22, c), mat(19, c, 0.1)) ++ kinds.flatMap { pools =>
      if pools then
        norm(c) ++ Vector(conv(3, c, reg), conv(3, c, pooled)) ++ norm(pooled) ++
          Vector(mat(3 * pooled, reg, math.sqrt(1.0 / (3 * pooled)))) ++ norm(reg) ++ Vector(conv(3, reg, c, true))
      else norm(c) ++ Vector(conv(3, c, c)) ++ norm(c) ++ Vector(conv(3, c, c, true))
    } ++ norm(c)
    val policy = Vector(conv(1, c, head), conv(1, c, head)) ++ norm(head) ++
      Vector(mat(3 * head, head, math.sqrt(1.0 / (3 * head)))) ++ norm(head) ++
      Vector(conv(1, head, 1, std = 0.01), mat(3 * head, 1, 0.01))
    val value = Vector(conv(1, c, head)) ++ norm(head) ++
      Vector(mat(3 * head, valueHidden, math.sqrt(2.0 / (3 * head))), bias(valueHidden),
        mat(valueHidden, 3, 0.01), bias(3), mat(valueHidden, 4, 0.01), bias(4), conv(1, head, 1, std = 0.01))
    trunk ++ policy ++ value
