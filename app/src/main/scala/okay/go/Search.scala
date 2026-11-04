package okay.go

import okay.go.Rules.{Black, Empty, White}

/** A FAST BOARD FOR PLAYOUTS, of any size: arrays and marks, no allocation on a move; simple ko (the exact rules,
 * superko included, are `Rules`' — the search's first moves are taken from there) */
final class Fast private (val geo: Geometry, val board: Array[Int], var toPlay: Int, var ko: Int, var passes: Int,
    var last: Int):
  private val near = geo.near
  def copy(): Fast = Fast(geo, board.clone(), toPlay, ko, passes, last)

  private val mark = new Array[Int](geo.count)
  private var stamp = 0
  private val stack = new Array[Int](geo.count)
  /** the liberty found last by `libs` */
  private var aLiberty = -1

  /** the liberties of the group at `p`, counted up to `most`; `aLiberty` one of them */
  def libs(p: Int, most: Int = 2): Int =
    stamp += 1
    val c = board(p); var top = 0; var found = 0
    stack(top) = p; top += 1; mark(p) = stamp
    while top > 0 do
      top -= 1
      val q = stack(top)
      val ns = near(q); var i = 0
      while i < ns.length do
        val n = ns(i)
        if mark(n) != stamp then
          if board(n) == Empty then
            mark(n) = stamp; found += 1; aLiberty = n
            if found >= most then return found
          else if board(n) == c then { mark(n) = stamp; stack(top) = n; top += 1 }
        i += 1
    found

  /** the group at `p` taken off: how many stones, and the last one's point */
  private def take(p: Int): (Int, Int) =
    val c = board(p); var top = 0; var n = 0; var lastStone = p
    stack(top) = p; top += 1; board(p) = Empty
    while top > 0 do
      top -= 1
      val q = stack(top); n += 1; lastStone = q
      val ns = near(q); var i = 0
      while i < ns.length do
        val m = ns(i)
        if board(m) == c then { board(m) = Empty; stack(top) = m; top += 1 }
        i += 1
    (n, lastStone)

  /** a stone of the side to play at `p`, if legal (not taken, not the ko point, not suicide) */
  def play(p: Int): Boolean =
    if board(p) != Empty || p == ko then return false
    val c = toPlay; val o = 3 - c
    board(p) = c
    var taken = 0; var lastTaken = -1
    val ns = near(p); var i = 0
    while i < ns.length do
      val n = ns(i)
      if board(n) == o && libs(n, 1) == 0 then { val (k, l) = take(n); taken += k; lastTaken = l }
      i += 1
    if taken == 0 && libs(p, 1) == 0 then { board(p) = Empty; return false }
    ko = if taken == 1 && alone(p) then lastTaken else -1
    toPlay = o; passes = 0; last = p
    true

  /** a stone with no friend next to it and one liberty: the shape a ko is taken by */
  private def alone(p: Int): Boolean =
    var free = 0; val ns = near(p); var i = 0
    while i < ns.length do
      val b = board(ns(i))
      if b == board(p) then return false
      if b == Empty then free += 1
      i += 1
    free == 1

  def pass(): Unit = { toPlay = 3 - toPlay; ko = -1; passes += 1; last = -1 }

  /** an eye of `c`: every neighbour `c`, and not too many enemy diagonals (one on the edge, two inside) */
  def eye(p: Int, c: Int): Boolean =
    if board(p) != Empty then return false
    val ns = near(p); var i = 0
    while i < ns.length do { if board(ns(i)) != c then return false; i += 1 }
    val ds = geo.diagonal(p); var enemy = 0; i = 0
    while i < ds.length do { if board(ds(i)) == 3 - c then enemy += 1; i += 1 }
    if ds.length < 4 then enemy == 0 else enemy < 2

  /** whether a stone of the side to play at `p` would capture something */
  def captures(p: Int): Boolean =
    val o = 3 - toPlay; val ns = near(p); var i = 0
    while i < ns.length do
      val n = ns(i)
      if board(n) == o && libs(n, 2) == 1 then return true
      i += 1
    false

  /** whether a stone of the side to play at `p` would put an enemy group of two liberties in atari */
  def ataris(p: Int): Boolean =
    val o = 3 - toPlay; val ns = near(p); var i = 0
    while i < ns.length do
      val n = ns(i)
      if board(n) == o && libs(n, 3) == 2 then return true
      i += 1
    false

  /** whether `p` is the last liberty of an own group, and a stone there gives it room again */
  def saves(p: Int): Boolean =
    val ns = near(p); var i = 0; var inAtari = false
    while i < ns.length do
      val n = ns(i)
      if board(n) == toPlay && libs(n, 2) == 1 then inAtari = true
      i += 1
    inAtari && !selfAtari(p)

  /** whether a stone of the side to play at `p` would leave its group (of two stones or more) with one liberty, not
   * capturing: the liberties it would have counted up to two */
  def selfAtari(p: Int): Boolean =
    if captures(p) then return false
    val c = toPlay; val ns = near(p)
    var free = 0; var friends = false; var i = 0
    var seen1 = -1
    while i < ns.length do
      val n = ns(i)
      if board(n) == Empty then { if n != seen1 then { free += 1; seen1 = n } }
      else if board(n) == c then friends = true
      i += 1
    if free >= 2 then return false
    if !friends then return false // a lone stone in atari is a throw-in, not a mistake as such
    board(p) = c
    val n = libs(p, 2)
    board(p) = Empty
    n <= 1

  /** THE PLAYOUT'S MOVE: a capture next to the last move; else saving an own group in atari next to it; else at
   * random, no own eye filled and a self-atari refused nine times in ten; a pass when nothing is left */
  def policyMove(r: scala.util.Random): Int =
    if last >= 0 then
      val o = 3 - toPlay
      // the last stone's own group, and its neighbours' groups of the opponent, in atari: taken
      if board(last) == o && libs(last, 2) == 1 then
        val l = aLiberty
        if play(l) then return l
      val ns = near(last); var i = 0
      while i < ns.length do
        val n = ns(i)
        if board(n) == o && libs(n, 2) == 1 then
          val l = aLiberty
          if play(l) then return l
        i += 1
      // an own group next to the last move in atari: out, if that gives it two liberties
      i = 0
      while i < ns.length do
        val n = ns(i)
        if board(n) == toPlay && libs(n, 2) == 1 then
          val l = aLiberty
          if !eye(l, toPlay) && !selfAtari(l) && play(l) then return l
        i += 1
    randomMove(r)

  private val cands = new Array[Int](geo.count)
  def randomMove(r: scala.util.Random): Int =
    var k = 0; var p = 0
    while p < geo.count do { if board(p) == Empty && !eye(p, toPlay) then { cands(k) = p; k += 1 }; p += 1 }
    while k > 0 do
      val i = r.nextInt(k); val q = cands(i)
      if (!selfAtari(q) || r.nextInt(10) == 0) && play(q) then return q
      k -= 1; cands(i) = cands(k)
    pass(); -1

  /** at a playout's end: Black's points less White's and komi — a stone its colour's, an empty point the colour of
   * all its neighbours (what is left at the end is eyes) */
  def score: Double =
    var b = 0; var w = 0; var p = 0
    while p < geo.count do
      val c = board(p)
      if c == Black then b += 1
      else if c == White then w += 1
      else
        val ns = near(p); var blacks = 0; var whites = 0; var i = 0
        while i < ns.length do
          if board(ns(i)) == Black then blacks += 1 else if board(ns(i)) == White then whites += 1
          i += 1
        if blacks == ns.length then b += 1 else if whites == ns.length then w += 1
      p += 1
    b - w - geo.komi

object Fast:
  def of(pos: Rules.Position): Fast =
    // a ko at the root is the rules' (superko): the root's moves come from `Rules.legal`
    new Fast(pos.geo, pos.board.toArray, pos.toPlay, -1, pos.passes, pos.last.getOrElse(-1))

/**
 * THE SEARCH: Monte-Carlo tree search with RAVE and prior knowledge. Games are played out from the position — down the
 * tree by the best estimate, then by the playout policy (`Fast.policyMove`) — and each one's winner counted for every
 * move on its way and (RAVE) for every move of the same colour at the same point anywhere after. A new node's moves
 * start with a few virtual games by what is known of them (a capture, saving a group, a self-atari, the lines). The
 * answer is the move played most; its share of wins the chance to win. It is run in parts (`run(n)`), so a page can
 * think between its frames.
 */
final class Searcher(pos: Rules.Position, r: scala.util.Random, heavy: Boolean = true, knowing: Boolean = true):
  import Searcher.*
  private val geo = pos.geo
  private val start = Fast.of(pos)
  val root: Node = Node(-1, Rules.other(pos.toPlay), null)
  root.children = priors(start, Rules.legal(pos).filterNot(p => start.eye(p, pos.toPlay)), 0)
  root.children.nn.foreach(_.parent = root)
  private val first = new Array[Int](geo.count)
  var playouts = 0
  private val moveCap = geo.count * 3

  /** what is known of each move before any game: a few virtual games, their share by what the move does */
  private def priors(f: Fast, moves: Seq[Int], depth: Int): Array[Node] =
    val opening = f.board.count(_ != Empty) < geo.count / 8
    moves.map { m =>
      val node = Node(m, f.toPlay, null)
      if knowing then
        val line = geo.line(m)
        var p = 0.5
        if f.captures(m) then p = 0.8
        else if f.saves(m) then p = 0.75
        else if f.selfAtari(m) then p = 0.15
        else if f.ataris(m) then p = 0.6
        if line == 0 then p -= 0.15
        if opening && (line == 2 || line == 3) then p += 0.1
        if opening && line == 1 then p -= 0.05
        if f.last >= 0 && near(m, f.last) then p += 0.05
        node.n = Virtual; node.wins = Virtual * math.max(0.02, math.min(0.98, p))
      node
    }.toArray

  private def near(a: Int, b: Int): Boolean =
    math.abs(a / geo.size - b / geo.size) <= 2 && math.abs(a % geo.size - b % geo.size) <= 2

  private def value(c: Node, parentN: Int): Double =
    val beta = math.sqrt(K / (3.0 * c.n + K))
    (1 - beta) * c.rate + beta * c.raveRate + 0.2 * math.sqrt(math.log(parentN + 1.0) / (c.n + 1.0))

  /** `n` more games played out */
  def run(n: Int): Unit =
    var left = n
    while left > 0 && root.children.nn.nonEmpty do
      one(); left -= 1; playouts += 1

  private def one(): Unit =
    val f = start.copy()
    java.util.Arrays.fill(first, 0)
    var node = root
    var going = true
    while going do
      if node.children == null then
        if node.n > (if knowing then Virtual else 0) || (node eq root) then
          val moves = geo.points.filter(p => f.board(p) == Empty && !f.eye(p, f.toPlay))
          val kids = priors(f, moves, 1)
          kids.foreach(_.parent = node)
          node.children = kids
        else going = false
      if going then
        val kids = node.children.nn
        if kids.isEmpty then going = false
        else
          var best = kids(0); var bv = -1.0; var j = 0
          while j < kids.length do
            val v = value(kids(j), node.n)
            if v > bv then { bv = v; best = kids(j) }
            j += 1
          if f.play(best.move) then
            if first(best.move) == 0 then first(best.move) = best.colour
            if best.parent == null then best.parent = node
            node = best
            going = node.n > (if knowing then Virtual else 0)
          else node.children = kids.filterNot(_ eq best) // illegal here (a ko or a suicide): gone
    var steps = 0
    while f.passes < 2 && steps < moveCap do
      val mover = f.toPlay
      val p = if heavy then f.policyMove(r) else f.randomMove(r)
      if p >= 0 && first(p) == 0 then first(p) = mover
      steps += 1
    val winner = if f.score > 0 then Black else White
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

  /** the move played most, with its share of wins (none: nothing worth playing) */
  def best: Option[Node] = root.children.nn.maxByOption(_.n)

object Searcher:
  /** virtual games of a prior; RAVE's weight sqrt(K / (3n + K)) */
  val Virtual = 8
  val K = 800.0

  final class Node(val move: Int, val colour: Int, var parent: Node | Null):
    var n = 0; var wins = 0.0
    var raveN = 0; var raveWins = 0.0
    var children: Array[Node] | Null = null
    def rate: Double = if n == 0 then 0.5 else wins / n
    def raveRate: Double = if raveN == 0 then 0.5 else raveWins / raveN
