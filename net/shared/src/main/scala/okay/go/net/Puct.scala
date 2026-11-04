package okay.go.net

import okay.go.Rules
import okay.go.Rules.{Black, Position}

/** what a network says of a position, ready for a search: a prior for every point and, last, the pass (0 where not
 * legal), and the value for the side to move, from -1 (lost) to 1 (won) */
final case class Judgement(priors: Array[Float], value: Double, legal: Option[Vector[Int]] = None)

object Judgement:
  /** the network's raw outputs made a judgement as KataGo's postprocessing does: the policy's softmax over the legal
   * points and the pass; the value win minus loss, "no result" taken out (positional superko, area scoring) */
  def of(pos: Position, raw: Raw): Judgement = of(okay.go.Scan(pos), raw)

  /** from a scan of the position (its legal points), made once for the inputs too */
  def of(scan: okay.go.Scan, raw: Raw): Judgement =
    val pos = scan.pos
    val area = pos.board.length
    val legal = scan.legal
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
    Judgement(e.map(x => (x / z).toFloat), (pw - pl) / (pw + pl), Some(legal))

  /** a network on the CPU, as a judge */
  def by(model: Model): Position => Judgement =
    val forward = Forward(model)
    pos =>
      val scan = okay.go.Scan(pos)
      val (s, g) = Inputs.of(scan)
      of(scan, forward.run(pos.size, s, g))

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
    /** waiting for its judgement, in a batch */
    var pending = false
    def q: Double = if n == 0 then 0 else w / n

  val top: Node = Node(root, 1f)
  var visits = 0

  /** the value of a finished game for the side to move in it */
  private def scored(pos: Position): Double =
    val (b, w) = Rules.score(pos.board)
    val blackWins = b > w
    if (pos.toPlay == Black) == blackWins then 1.0 else -1.0

  private def expandBy(node: Node, j: Judgement): Double =
    // the judgement's legal points when it has them (a scan made once), else the rules'
    val legal = j.legal.getOrElse(Rules.legal(node.pos)) :+ node.pos.passIndex
    node.moves = legal.toArray
    node.children = legal.map(m => Node(null, j.priors(m))).toArray
    j.value

  /** `n` more visits, each judged at once */
  def run(n: Int): Unit =
    var left = n
    while left > 0 do
      gather(1).foreach(p => complete(p, judge(p.leaf.pos)))
      left -= 1

  /** A LEAF TO JUDGE, its path from the root (the leaf first) */
  final class Leaf(val path: List[Node], val leaf: Node)

  /** up to `max` leaves to judge together: each chosen with a VIRTUAL LOSS on its path, so the next is another; a
   * finished game scored at once; stopped at a leaf already waiting */
  def gather(max: Int): Vector[Leaf] =
    val out = Vector.newBuilder[Leaf]; var count = 0; var stop = false; var tries = 0
    while count < max && !stop && tries < 4 * max do
      tries += 1
      var path = List(top); var node = top; top.n += 1
      var walking = true
      while walking do
        if node.pos.over then
          walking = false
          backup(path, scored(node.pos)); visits += 1; count += 1
        else if node.children == null then
          walking = false
          if node.pending then { unwind(path); stop = true }
          else { node.pending = true; out += Leaf(path, node); count += 1 }
        else
          val kids = node.children.nn
          val sqrtN = math.sqrt(math.max(1, node.n).toDouble)
          val parentQ = if node.n == 0 then 0.0 else -node.q
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
          node.n += 1; node.w -= 1 // the virtual loss, from the chooser's view
          path = node :: path
    out.result()

  /** the virtual losses of a path taken back */
  private def unwind(path: List[Node]): Unit =
    for p <- path do { p.n -= 1; if !(p eq top) then p.w += 1 }

  /** a leaf's judgement: its children by the policy, its value backed up */
  def complete(l: Leaf, j: Judgement): Unit =
    l.leaf.pending = false
    backup(l.path, expandBy(l.leaf, j))
    visits += 1

  /** `value` for the side to move at the path's leaf, backed up, the path's virtual losses taken back */
  private def backup(path: List[Node], value: Double): Unit =
    unwind(path)
    var v = -value
    for p <- path do { p.n += 1; p.w += v; v = -v }

  /** the move played most at the root (the pass index for a pass), and its chance as the root's player sees it */
  def best: (Int, Double) =
    val kids = top.children.nn
    // most visited; among equals (a search of one visit: none visited) the network's own choice
    val i = kids.indices.maxBy(k => (kids(k).n, kids(k).prior))
    (top.moves(i), (kids(i).q + 1) / 2)
