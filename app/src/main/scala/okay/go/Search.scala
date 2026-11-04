package okay.go

import okay.go.Rules.{Black, Empty, N, White}

/** A FAST BOARD FOR PLAYOUTS: arrays and marks, no allocation on a move; simple ko (the exact rules, superko
 * included, are `Rules`' — the search's first moves are taken from there) */
final class Fast private (val board: Array[Int], var toPlay: Int, var ko: Int, var passes: Int):
  import Fast.*
  def copy(): Fast = Fast(board.clone(), toPlay, ko, passes)

  private val mark = new Array[Int](N * N)
  private var stamp = 0
  private val stack = new Array[Int](N * N)

  /** whether the group at `p` has a liberty */
  private def free(p: Int): Boolean =
    stamp += 1
    val c = board(p); var top = 0
    stack(top) = p; top += 1; mark(p) = stamp
    while top > 0 do
      top -= 1
      val q = stack(top)
      val ns = near(q); var i = 0
      while i < ns.length do
        val n = ns(i)
        if board(n) == Empty then return true
        if board(n) == c && mark(n) != stamp then { mark(n) = stamp; stack(top) = n; top += 1 }
        i += 1
    false

  /** the group at `p` taken off: how many stones, and the last one's point */
  private def take(p: Int): (Int, Int) =
    val c = board(p); var top = 0; var n = 0; var last = p
    stack(top) = p; top += 1; board(p) = Empty
    while top > 0 do
      top -= 1
      val q = stack(top); n += 1; last = q
      val ns = near(q); var i = 0
      while i < ns.length do
        val m = ns(i)
        if board(m) == c then { board(m) = Empty; stack(top) = m; top += 1 }
        i += 1
    (n, last)

  /** a stone of the side to play at `p`, if legal (not taken, not the ko point, not suicide) */
  def play(p: Int): Boolean =
    if board(p) != Empty || p == ko then return false
    val c = toPlay; val o = 3 - c
    board(p) = c
    var taken = 0; var last = -1
    val ns = near(p); var i = 0
    while i < ns.length do
      val n = ns(i)
      if board(n) == o && !free(n) then { val (k, l) = take(n); taken += k; last = l }
      i += 1
    if taken == 0 && !free(p) then { board(p) = Empty; return false }
    ko = if taken == 1 && alone(p) then last else -1
    toPlay = o; passes = 0
    true

  /** a stone with no friend next to it and one liberty: the shape a ko is taken by */
  private def alone(p: Int): Boolean =
    var libs = 0; val ns = near(p); var i = 0
    while i < ns.length do
      val b = board(ns(i))
      if b == board(p) then return false
      if b == Empty then libs += 1
      i += 1
    libs == 1

  def pass(): Unit = { toPlay = 3 - toPlay; ko = -1; passes += 1 }

  /** an eye of `c`: every neighbour `c`, and not too many enemy diagonals (one on the edge, two inside) */
  def eye(p: Int, c: Int): Boolean =
    if board(p) != Empty then return false
    val ns = near(p); var i = 0
    while i < ns.length do { if board(ns(i)) != c then return false; i += 1 }
    val ds = diagonal(p); var enemy = 0; i = 0
    while i < ds.length do { if board(ds(i)) == 3 - c then enemy += 1; i += 1 }
    if ds.length < 4 then enemy == 0 else enemy < 2

  /** a random legal move that does not fill an own eye, or a pass; the point played (or -1) */
  def randomMove(r: scala.util.Random): Int =
    val cands = new Array[Int](N * N); var k = 0; var p = 0
    while p < N * N do { if board(p) == Empty && !eye(p, toPlay) then { cands(k) = p; k += 1 }; p += 1 }
    while k > 0 do
      val i = r.nextInt(k); val q = cands(i)
      if play(q) then return q
      k -= 1; cands(i) = cands(k)
    pass(); -1

  /** Black's area less White's and komi (Tromp-Taylor) */
  def score: Double =
    var b = 0; var w = 0
    val a = Rules.area(board.toVector)
    var p = 0
    while p < N * N do { if a(p) == Black then b += 1 else if a(p) == White then w += 1; p += 1 }
    b - w - Rules.komi

object Fast:
  val near: Array[Array[Int]] = Rules.neighbours.map(_.toArray).toArray
  val diagonal: Array[Array[Int]] = Array.tabulate(N * N) { p =>
    val (r, c) = (p / N, p % N)
    Array((r - 1, c - 1), (r - 1, c + 1), (r + 1, c - 1), (r + 1, c + 1)).collect {
      case (a, b) if a >= 0 && a < N && b >= 0 && b < N => a * N + b
    }
  }
  def of(pos: Rules.Position): Fast =
    val koPoint = -1 // a ko at the root is the rules' (superko): the root's moves come from `Rules.legal`
    new Fast(pos.board.toArray, pos.toPlay, koPoint, pos.passes)

/**
 * THE SEARCH: Monte-Carlo tree search with RAVE. From the position, `playouts` games are played to the end — down
 * the tree by the best estimate, then at random (no own eye filled) — and each one's winner counted for every move
 * on its way, and (RAVE) for every move of the same colour at the same point anywhere after. The move played most is
 * the answer; its share of wins the chance to win. The root's moves are the exact rules' (superko); deeper, simple
 * ko.
 */
object Search:
  final class Node(val move: Int, val colour: Int, val parent: Node | Null):
    var n = 0; var wins = 0.0
    var raveN = 0; var raveWins = 0.0
    var children: Array[Node] | Null = null
    def rate: Double = if n == 0 then 0.5 else wins / n
    def raveRate: Double = if raveN == 0 then 0.5 else raveWins / raveN

  /** RAVE's weight falls as a move's own games grow: sqrt(k / (3n + k)) */
  private val k = 600.0

  private def value(c: Node, parentN: Int): Double =
    val beta = math.sqrt(k / (3.0 * c.n + k))
    (1 - beta) * c.rate + beta * c.raveRate + 0.25 * math.sqrt(math.log(parentN + 1.0) / (c.n + 1.0))

  private def expand(node: Node, f: Fast, moves: Seq[Int]): Unit =
    val mover = f.toPlay
    node.children = moves.map(m => Node(m, mover, node)).toArray

  /** the moves from `f` worth searching: legal, no own eye filled */
  private def candidates(f: Fast): Vector[Int] =
    (0 until N * N).filter(p => f.board(p) == Empty && !f.eye(p, f.toPlay) && f.copy().play(p)).toVector

  final case class Result(root: Node, playouts: Int)

  /** at most `playouts` games, and no more once `millis` have passed (checked every 32 games) */
  def run(pos: Rules.Position, playouts: Int, r: scala.util.Random, millis: Long = Long.MaxValue): Result =
    val deadline = if millis == Long.MaxValue then Long.MaxValue else System.nanoTime() + millis * 1000000L
    val start = Fast.of(pos)
    val root = Node(-1, Rules.other(pos.toPlay), null)
    val rootMoves = Rules.legal(pos).filterNot(p => start.eye(p, pos.toPlay))
    expand(root, start, rootMoves)
    val first = new Array[Int](N * N)
    var i = 0
    while i < playouts && root.children.nn.nonEmpty && (i % 32 != 0 || System.nanoTime() < deadline) do
      val f = start.copy()
      java.util.Arrays.fill(first, 0)
      var node = root
      // down the tree
      var going = true
      while going do
        val cs = node.children
        if cs == null then
          if node.n > 0 || (node eq root) then { expand(node, f, candidates(f)); going = node.children.nn.nonEmpty }
          else going = false
        else if cs.nn.isEmpty then going = false
        if going then
          val kids = node.children.nn
          var best = kids(0); var bv = -1.0; var j = 0
          while j < kids.length do
            val v = value(kids(j), node.n)
            if v > bv then { bv = v; best = kids(j) }
            j += 1
          if f.play(best.move) then
            if first(best.move) == 0 then first(best.move) = best.colour
            node = best
            going = node.n > 0
          else
            node.children = kids.filterNot(_ eq best) // illegal here (a ko): gone
            going = node.children.nn.nonEmpty
      // at random to the end
      var steps = 0
      while f.passes < 2 && steps < 200 do
        val mover = f.toPlay
        val p = f.randomMove(r)
        if p >= 0 && first(p) == 0 then first(p) = mover
        steps += 1
      val winner = if f.score > 0 then Black else White
      // back up, with RAVE for the siblings played later at the same point by the same colour
      var up: Node | Null = node
      while up != null do
        val u = up.nn
        u.n += 1
        if u.colour == winner then u.wins += 1
        val kids = u.children
        if kids != null then
          var j = 0
          while j < kids.length do
            val c = kids(j)
            if first(c.move) == c.colour then { c.raveN += 1; if c.colour == winner then c.raveWins += 1 }
            j += 1
        up = u.parent
      i += 1
    Result(root, i)
