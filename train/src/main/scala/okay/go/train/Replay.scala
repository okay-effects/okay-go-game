package okay.go.train

/**
 * THE REPLAY BUFFER of one board size, in memory, a ring: each position's inputs (spatial as bytes — every feature
 * 0 or 1 — channel first, as `Inputs` makes them; global as floats) and the teacher's answers to learn: the policy
 * over the points and the pass (a distribution, the legal moves alone), the value (win, loss, no result), the score
 * (the first score output, raw), ownership (after tanh). A batch is drawn at random.
 */
final class Replay(val size: Int, val capacity: Int):
  val area: Int = size * size
  private val spatial = new Array[Byte](capacity * 22 * area)
  private val global = new Array[Float](capacity * 19)
  private val policy = new Array[Float](capacity * (area + 1))
  private val value = new Array[Float](capacity * 3)
  private val score = new Array[Float](capacity)
  private val own = new Array[Float](capacity * area)
  private var next = 0
  var count = 0
  var added = 0L

  def add(s: Array[Float], g: Array[Float], p: Array[Float], v: Array[Float], sc: Float, o: Array[Float]): Unit =
    val k = next
    var i = 0
    while i < s.length do { spatial(k * 22 * area + i) = if s(i) != 0f then 1 else 0; i += 1 }
    System.arraycopy(g, 0, global, k * 19, 19)
    System.arraycopy(p, 0, policy, k * (area + 1), area + 1)
    System.arraycopy(v, 0, value, k * 3, 3)
    score(k) = sc
    System.arraycopy(o, 0, own, k * area, area)
    next = (next + 1) % capacity; count = math.min(capacity, count + 1); added += 1

  /** a batch, as flat arrays: spatial [B, 22, area], global [B, 19], policy [B, area + 1], value [B, 3], score [B],
   * ownership [B, area] */
  final class Batch(val n: Int, val spatial: Array[Float], val global: Array[Float], val policy: Array[Float],
      val value: Array[Float], val score: Array[Float], val own: Array[Float])

  def batch(n: Int, random: scala.util.Random): Batch = of(Vector.fill(n)(random.nextInt(count)))

  def of(ks: Vector[Int]): Batch =
    val n = ks.size
    val b = Batch(n, new Array[Float](n * 22 * area), new Array[Float](n * 19), new Array[Float](n * (area + 1)),
      new Array[Float](n * 3), new Array[Float](n), new Array[Float](n * area))
    for (k, j) <- ks.zipWithIndex do
      var i = 0; val from = k * 22 * area; val to = j * 22 * area
      while i < 22 * area do { b.spatial(to + i) = spatial(from + i); i += 1 }
      System.arraycopy(global, k * 19, b.global, j * 19, 19)
      System.arraycopy(policy, k * (area + 1), b.policy, j * (area + 1), area + 1)
      System.arraycopy(value, k * 3, b.value, j * 3, 3)
      b.score(j) = score(k)
      System.arraycopy(own, k * area, b.own, j * area, area)
    b
