package okay.go.net

import okay.go.Rules
import okay.go.Rules.{Black, Position}

/** what a network says of a position, ready for a search: a prior for every point and, last, the pass (0 where not
 * legal), and the value for the side to move, from -1 (lost) to 1 (won) */
final case class Judgement(priors: Array[Float], value: Double)

object Judgement:
  /** the network's raw outputs made a judgement as KataGo's postprocessing does: the policy's softmax over the legal
   * points and the pass; the value win minus loss, "no result" taken out (positional superko, area scoring) */
  def of(pos: Position, raw: Raw): Judgement =
    val area = pos.board.length
    val legal = Rules.legal(pos)
    val logits = new Array[Double](area + 1)
    java.util.Arrays.fill(logits, Double.NegativeInfinity)
    legal.foreach(p => logits(p) = raw.policy(p))
    logits(area) = raw.pass
    val mx = logits.max
    val e = logits.map(l => if l.isInfinite then 0.0 else math.exp(l - mx))
    val z = e.sum
    val (w, l) = (raw.value(0).toDouble, raw.value(1).toDouble)
    val m = math.max(w, l)
    val pw = math.exp(w - m); val pl = math.exp(l - m)
    Judgement(e.map(x => (x / z).toFloat), (pw - pl) / (pw + pl))

  /** a network on the CPU, as a judge */
  def by(model: Model): Position => Judgement =
    val forward = Forward(model)
    pos =>
      val (s, g) = Inputs.of(pos)
      of(pos, forward.run(pos.size, s, g))

/**
 * THE SEARCH WITH A NETWORK (PUCT, as AlphaZero and KataGo search): a tree of positions; a leaf judged by the network
 * — its policy the children's priors, its value backed up — never played out. A child is chosen by
 * Q + c * P * sqrt(N) / (1 + n), an unvisited child's Q its parent's less a first-play reduction; a finished game is
 * scored by the rules. The move played most is the answer.
 */
final class Puct(root: Position, judge: Position => Judgement, c: Double = 1.1, fpu: Double = 0.2):
  final class Node(val pos: Position, val prior: Float):
    var n = 0
    /** the values backed up, from the view of the player who chose this node (the one not to move in it) */
    var w = 0.0
    var children: Array[Node] | Null = null
    var moves: Array[Int] = Array.empty
    def q: Double = if n == 0 then 0 else w / n

  val top: Node = Node(root, 1f)
  var visits = 0

  /** the value of a finished game for the side to move in it */
  private def scored(pos: Position): Double =
    val (b, w) = Rules.score(pos.board)
    val blackWins = b > w
    if (pos.toPlay == Black) == blackWins then 1.0 else -1.0

  private def expand(node: Node): Double =
    val j = judge(node.pos)
    val legal = Rules.legal(node.pos) :+ node.pos.passIndex
    node.moves = legal.toArray
    node.children = legal.map(m => Node(null, j.priors(m))).toArray
    j.value

  /** `n` more visits */
  def run(n: Int): Unit =
    var left = n
    while left > 0 do { one(); left -= 1; visits += 1 }

  private def one(): Unit =
    var path = List(top); var node = top
    var value = 0.0; var done = false
    while !done do
      if node.pos.over then { value = scored(node.pos); done = true }
      else if node.children == null then { value = expand(node); done = true }
      else
        val kids = node.children.nn
        val sqrtN = math.sqrt(math.max(1, node.n).toDouble)
        val parentQ = if node.n == 0 then 0.0 else -node.q // from the view of the side to move at `node`
        var best = 0; var bv = Double.NegativeInfinity; var i = 0
        while i < kids.length do
          val k = kids(i)
          val q = if k.n == 0 then parentQ - fpu else k.q
          val v = q + c * k.prior * sqrtN / (1 + k.n)
          if v > bv then { bv = v; best = i }
          i += 1
        val k = kids(best)
        if k.pos == null then
          val m = node.moves(best)
          val next = if m == node.pos.passIndex then Rules.pass(node.pos) else Rules.play(node.pos, m)
          kids(best) = Node(next, k.prior)
        node = kids(best)
        path = node :: path
    // `value` is for the side to move at the leaf; each node keeps it from its chooser's view
    var v = -value
    for p <- path do { p.n += 1; p.w += v; v = -v }

  /** the move played most at the root (the pass index for a pass), and its chance as the root's player sees it */
  def best: (Int, Double) =
    val kids = top.children.nn
    // most visited; among equals (a search of one visit: none visited) the network's own choice
    val i = kids.indices.maxBy(k => (kids(k).n, kids(k).prior))
    (top.moves(i), (kids(i).q + 1) / 2)
