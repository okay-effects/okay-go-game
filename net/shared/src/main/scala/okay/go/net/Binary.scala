package okay.go.net

import java.nio.{ByteBuffer, ByteOrder}

/**
 * A MODEL AS BYTES, for a page to load at once (the text is ~40 MB to parse): little-endian 32-bit words — a magic,
 * the version, the inputs' counts, the blocks' count and kinds, then every layer in the text's order as its sizes
 * followed by its floats (a batchnorm already merged: its scale, its bias). Names are not kept.
 */
object Binary:
  private val Magic = 0x6f6b6731 // "okg1"

  def write(m: Model): Array[Byte] =
    val ints = scala.collection.mutable.ArrayBuffer.empty[Int]
    val floats = scala.collection.mutable.ArrayBuffer.empty[Array[Float]]
    // the layout: a list of words, each either an int or a reference to a float array, written in order
    val out = scala.collection.mutable.ArrayBuffer.empty[Either[Int, Array[Float]]]
    def i(x: Int): Unit = out += Left(x)
    def f(a: Array[Float]): Unit = out += Right(a)
    def conv(c: Conv): Unit = { i(c.ky); i(c.kx); i(c.in); i(c.out); f(c.w) }
    def norm(b: Norm): Unit = { i(b.channels); f(b.scale); f(b.bias) }
    def mat(x: MatMul): Unit = { i(x.in); i(x.out); f(x.w) }
    def bias(x: MatBias): Unit = { i(x.channels); f(x.b) }
    i(Magic); i(m.version); i(m.spatial); i(m.global); i(m.blocks.size)
    m.blocks.foreach { case _: Ordinary => i(0); case _: Pooling => i(1) }
    conv(m.conv1); mat(m.ginput)
    m.blocks.foreach {
      case Ordinary(n1, w1, n2, w2) => norm(n1); conv(w1); norm(n2); conv(w2)
      case Pooling(n1, a, b, nb, r, n2, w2) => norm(n1); conv(a); conv(b); norm(nb); mat(r); norm(n2); conv(w2)
    }
    norm(m.trunkNorm)
    conv(m.p1); conv(m.g1); norm(m.g1Norm); mat(m.g2); norm(m.p1Norm); conv(m.p2); mat(m.pass)
    conv(m.v1); norm(m.v1Norm); mat(m.v2); bias(m.v2b); mat(m.v3); bias(m.v3b); mat(m.sv3); bias(m.sv3b); conv(m.own)
    val words = out.map { case Left(_) => 1; case Right(a) => a.length }.sum
    val bb = ByteBuffer.allocate(words * 4).order(ByteOrder.LITTLE_ENDIAN)
    out.foreach { case Left(x) => bb.putInt(x); case Right(a) => a.foreach(bb.putFloat) }
    val _ = (ints, floats)
    bb.array()

  def read(bytes: Array[Byte]): Model =
    val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    def i(): Int = bb.getInt()
    def f(n: Int): Array[Float] = Array.fill(n)(bb.getFloat())
    def conv(name: String): Conv =
      val ky = i(); val kx = i(); val in = i(); val out = i()
      Conv(name, ky, kx, in, out, f(ky * kx * in * out))
    def norm(name: String): Norm = { val c = i(); Norm(name, c, f(c), f(c)) }
    def mat(name: String): MatMul = { val in = i(); val out = i(); MatMul(name, in, out, f(in * out)) }
    def bias(name: String): MatBias = { val c = i(); MatBias(name, c, f(c)) }
    require(i() == Magic, "not a model of this format")
    val version = i(); val spatial = i(); val global = i(); val nb = i()
    val kinds = Vector.fill(nb)(i())
    val conv1 = conv("conv1"); val ginput = mat("ginputw")
    val blocks = kinds.map {
      case 0 => Ordinary(norm("norm1"), conv("w1"), norm("norm2"), conv("w2"))
      case _ => Pooling(norm("norm1"), conv("w1a"), conv("w1b"), norm("norm1b"), mat("w1r"), norm("norm2"), conv("w2"))
    }
    val trunkNorm = norm("trunk/norm")
    val p1 = conv("p1"); val g1 = conv("g1"); val g1n = norm("g1/norm"); val g2 = mat("g2"); val p1n = norm("p1/norm")
    val p2 = conv("p2"); val pass = mat("pass")
    val v1 = conv("v1"); val v1n = norm("v1/norm"); val v2 = mat("v2"); val v2b = bias("v2/b"); val v3 = mat("v3")
    val v3b = bias("v3/b"); val sv3 = mat("sv3"); val sv3b = bias("sv3/b"); val own = conv("own")
    Model("binary", version, spatial, global, conv1, ginput, blocks, trunkNorm, p1, g1, g1n, g2, p1n, p2, pass,
      v1, v1n, v2, v2b, v3, v3b, sv3, sv3b, own)
