package okay.go.net

import okay.go.{Rules, Scan}
import okay.go.Rules.{Black, Board, Empty, Position, White}

/**
 * THE NETWORK'S INPUTS, version 7 (KataGo's `fillRowV7`), from the side to move's view, under the rules this game
 * plays — positional superko, area scoring, no suicide, no button, no encore, komi the board's:
 *   spatial (22 x n x n): 0 every point; 1, 2 own and opponent stones; 3–5 stones of chains with 1, 2, 3 liberties;
 *   6 points a move may not be played for ko (a repeated position); 9–13 the last five moves (not passes); 14–16
 *   stones of chains caught in a ladder now, a move ago, two moves ago; 17 the moves that catch an opponent chain of
 *   two liberties in a ladder; 18, 19 own and opponent area by pass-alive regions (Benson); 7, 8, 20, 21 zero.
 *   global (19): 0–4 which of the last five moves were passes; 5 own komi / 20; 6 = 1, 7 = 0.5 (positional ko);
 *   14 whether a pass would end the game; 18 the komi's parity wave; the rest 0.
 */
object Inputs:
  def of(pos: Position): (Array[Float], Array[Float]) = of(Scan(pos))

  /** from a scan of the position, made once for the search too */
  def of(scan: Scan): (Array[Float], Array[Float]) =
    val pos = scan.pos
    val n = pos.size; val area = n * n
    val pla = pos.toPlay; val opp = Rules.other(pla)
    val b = pos.board
    val spatial = new Array[Float](22 * area)
    val global = new Array[Float](19)
    def set(f: Int, p: Int): Unit = spatial(f * area + p) = 1f
    for p <- 0 until area do
      set(0, p)
      if b(p) == pla then set(1, p) else if b(p) == opp then set(2, p)
    for p <- 0 until area if b(p) != Empty do
      scan.libertiesAt(p) match
        case 1 => set(3, p)
        case 2 => set(4, p)
        case 3 => set(5, p)
        case _ => ()
    scan.repeats.foreach(set(6, _))
    // the last five moves, alternating by colour as a game's do
    val history = math.min(5, pos.record.size)
    for k <- 1 to history do
      val m = pos.record(pos.record.size - k)
      if m == pos.passIndex then global(k - 1) = 1f else set(8 + k, m)
    // ladders now, a move ago, two moves ago
    Ladders.iterate(b, n, Ladders.koPoint(pos)) { (p, working) =>
      set(14, p)
      if b(p) == opp && scan.libertiesAt(p) > 1 then working.foreach(set(17, _))
    }
    val prev = if history < 1 then b else pos.boardAgo(1)
    val prevPrev = if history < 2 then prev else pos.boardAgo(2)
    Ladders.iterate(prev, n, -1)((p, _) => set(15, p))
    Ladders.iterate(prevPrev, n, -1)((p, _) => set(16, p))
    // the area of pass-alive regions
    val owner = Area.of(b, n)
    for p <- 0 until area do
      if owner(p) == pla then set(18, p) else if owner(p) == opp then set(19, p)
    // the globals
    val komi = pos.geo.komi
    val self = if pla == White then komi else -komi
    global(5) = (self / 20).toFloat
    global(6) = 1f; global(7) = 0.5f
    if pos.passes >= 1 then global(14) = 1f
    val even = area % 2 == 0
    val floor = if even then math.floor(self / 2) * 2 else math.floor((self - 1) / 2) * 2 + 1
    val delta = math.max(0.0, math.min(2.0, self - floor))
    global(18) = (if delta < 0.5 then delta else if delta < 1.5 then 1 - delta else delta - 2).toFloat
    (spatial, global)

/** chains of a board: each stone's chain (by its first point) and its liberties */
final class Chains(b: Board, n: Int):
  private val head = Array.fill(b.length)(-1)
  private val libs = new Array[Int](b.length)
  for p <- b.indices if b(p) != Empty && head(p) < 0 do
    val (stones, ls) = Rules.group(b, p)
    stones.foreach { s => head(s) = p; libs(s) = ls.size }
  def liberties(p: Int): Int = libs(p)
  def chain(p: Int): Int = head(p)

/** AREA BY PASS-ALIVE REGIONS (KataGo's `calculateArea`, Benson's algorithm, suicide illegal): each colour's
 * pass-alive chains and the regions they settle; then every other stone counts for its colour, the rest is nobody's */
object Area:
  def of(b: Board, n: Int): Array[Int] =
    val result = Array.fill(b.length)(Empty)
    forColour(b, n, Black, result); forColour(b, n, White, result)
    for p <- b.indices if result(p) == Empty do result(p) = b(p)
    result

  private def forColour(b: Board, n: Int, pla: Int, result: Array[Int]): Unit =
    val near = okay.go.Geometry(n).near
    val opp = Rules.other(pla)
    val chains = Chains(b, n)
    val region = Array.fill(b.length)(-1)
    final class Region(val points: Vector[Int], val vital: Set[Int], val internal: Int, val containsOpp: Boolean):
      var bordersDead = false
    val regions = scala.collection.mutable.ArrayBuffer.empty[Region]
    for seed <- b.indices if b(seed) == Empty && region(seed) < 0 do
      val id = regions.size
      var todo = List(seed); var pts = Vector(seed); region(seed) = id
      while todo.nonEmpty do
        val q = todo.head; todo = todo.tail
        for m <- near(q) if b(m) != pla && region(m) < 0 do { region(m) = id; pts :+= m; todo = m :: todo }
      def adjacentChains(p: Int): Set[Int] = near(p).filter(b(_) == pla).map(chains.chain).toSet
      var vital = adjacentChains(seed)
      for p <- pts if b(p) == Empty do vital = vital intersect adjacentChains(p)
      val internal = math.min(2, pts.count(p => !near(p).exists(b(_) == pla)))
      regions += Region(pts, vital, internal, pts.exists(b(_) == opp))
    val atLeastOnePla = b.contains(pla)
    val heads = b.indices.filter(p => b(p) == pla).map(chains.chain).distinct
    val vitalCount = scala.collection.mutable.Map.empty[Int, Int].withDefaultValue(0)
    for r <- regions; c <- r.vital do vitalCount(c) += 1
    val dead = scala.collection.mutable.Set.empty[Int]
    var changed = true
    while changed do
      changed = false
      for h <- heads if !dead(h) && vitalCount(h) < 2 do
        dead += h; changed = true
        val stones = b.indices.filter(p => b(p) == pla && chains.chain(p) == h)
        for s <- stones; m <- near(s) if region(m) >= 0 do
          val r = regions(region(m))
          if !r.bordersDead then { r.bordersDead = true; r.vital.foreach(c => vitalCount(c) -= 1) }
    for p <- b.indices if b(p) == pla && !dead(chains.chain(p)) do result(p) = pla
    for r <- regions do
      if (r.internal <= 1 && !r.bordersDead && atLeastOnePla) || (!r.containsOpp && !r.bordersDead && atLeastOnePla)
      then r.points.foreach(result(_) = pla)
      else if !r.containsOpp && atLeastOnePla then r.points.foreach(p => if result(p) == Empty then result(p) = pla)

/**
 * LADDERS (KataGo's `iterLadders` and `searchIsLadderCaptured`): for each chain of one or two liberties, whether
 * it is caught in a ladder — a minimax where the defender extends or captures to gain liberties and the attacker
 * plays one of two liberties; at most 25 000 nodes (beyond that: not caught). A chain of two liberties is tried with
 * the attacker first, at each liberty; the moves that work are given.
 */
object Ladders:
  /** the simple-ko point after the last move: it captured exactly one stone and stands alone with one liberty */
  def koPoint(pos: Position): Int = pos.last match
    case None => -1
    case Some(p) =>
      val before = pos.boardAgo(1)
      val taken = pos.board.indices.filter(q => before(q) == Rules.other(pos.board(p)) && pos.board(q) == Empty)
      val near = pos.geo.near(p)
      val alone = !near.exists(pos.board(_) == pos.board(p))
      if taken.size == 1 && alone && Rules.group(pos.board, p)._2.size == 1 then taken.head else -1

  def iterate(board: Board, n: Int, ko: Int)(found: (Int, Vector[Int]) => Unit): Unit =
    val chains = Chains(board, n)
    val solved = scala.collection.mutable.Map.empty[Int, Boolean]
    val lb = LadderBoard(board.toArray, n, ko)
    for p <- board.indices if board(p) != Empty do
      val l = chains.liberties(p)
      if l == 1 || l == 2 then
        val h = chains.chain(p)
        solved.get(h) match
          case Some(true) => found(p, Vector.empty)
          case Some(false) => ()
          case None =>
            val (laddered, working) =
              if l == 1 then (lb.captured(p, defenderFirst = true), Vector.empty[Int]) else lb.attackerFirst(p)
            solved(h) = laddered
            if laddered then found(p, working)

final class LadderBoard(b: Array[Int], n: Int, var ko: Int):
  private val near = okay.go.Geometry(n).near
  private def chain(p: Int): (Vector[Int], Vector[Int]) =
    val c = b(p); var stones = Vector(p); var libs = Vector.empty[Int]
    val seen = scala.collection.mutable.Set(p); var todo = List(p)
    while todo.nonEmpty do
      val q = todo.head; todo = todo.tail
      for m <- near(q) if !seen(m) do
        seen += m
        if b(m) == Empty then libs :+= m
        else if b(m) == c then { stones :+= m; todo = m :: todo }
    (stones, libs)
  private def libsOf(p: Int): Int = chain(p)._2.size
  def isLegal(m: Int, c: Int): Boolean =
    if b(m) != Empty || m == ko then false
    else near(m).exists(q => b(q) == Empty ||
      (b(q) == c && libsOf(q) > 1) || (b(q) == 3 - c && libsOf(q) == 1))
  private final case class Undo(m: Int, taken: Vector[Int], ko: Int)
  private def play(m: Int, c: Int): Undo =
    b(m) = c
    var taken = Vector.empty[Int]
    for q <- near(m) if b(q) == 3 - c do
      val (stones, libs) = chain(q)
      if libs.isEmpty then { stones.foreach(b(_) = Empty); taken ++= stones }
    val was = ko
    ko = if taken.size == 1 && !near(m).exists(b(_) == c) && libsOf(m) == 1 then taken.head else -1
    Undo(m, taken, was)
  private def undo(u: Undo): Unit =
    val c = b(u.m); b(u.m) = Empty; u.taken.foreach(b(_) = 3 - c); ko = u.ko
  private def emptyNeighbours(m: Int): Int = near(m).count(b(_) == Empty)
  private def libsAfter(m: Int, c: Int): Int = { val u = play(m, c); val l = libsOf(m); undo(u); l }
  private def wouldBeKoCapture(m: Int, c: Int): Boolean =
    near(m).forall(b(_) == 3 - c) && {
      val ones = near(m).filter(q => libsOf(q) == 1).map(q => chain(q)._1).distinct
      ones.size == 1 && ones.head.size == 1
    }
  private def libertyGainingCaptures(p: Int): Vector[Int] =
    val c = b(p)
    chain(p)._1.flatMap(near(_)).filter(q => b(q) == 3 - c).map(chain).filter(_._2.size == 1)
      .map(_._2.head).distinct
  private def connX2(m: Int, c: Int): Int =
    near(m).filter(b(_) == c).map(libsOf).filter(_ > 1).map(l => 2 * l - 3).sum

  private final class Abort extends RuntimeException(null, null, false, false)

  /** whether the chain at `p` is caught: the defender to move first (`defenderFirst`) or the attacker */
  def captured(p: Int, defenderFirst: Boolean): Boolean =
    val pla = b(p); val opp = 3 - pla
    val rootLibs = libsOf(p)
    if rootLibs > 2 || (defenderFirst && rootLibs > 1) then return false
    val savedKo = ko
    if defenderFirst then ko = -1
    var nodes = 0
    val maxDepth = n * n * 3 / 2 + 1
    def solve(depth: Int): Boolean =
      if depth >= maxDepth - 1 then return true
      if nodes >= 25000 then throw Abort()
      val isDef = (depth % 2 == 0) == defenderFirst
      val l = libsOf(p)
      if !isDef && l <= 1 then return true
      if !isDef && l >= 3 then return false
      if isDef && l >= 2 then return false
      if isDef && ko >= 0 then return false
      var moves: Vector[Int] =
        if isDef then (libertyGainingCaptures(p) ++ chain(p)._2).distinct else chain(p)._2
      if isDef then
        val after = libsAfter(moves.last, pla)
        if after >= 3 then return false
        if moves.size == 1 && after <= 1 then return true
      else
        val Vector(a, c) = moves.take(2): @unchecked
        val la = emptyNeighbours(a); val lc = emptyNeighbours(c)
        if la == 0 && lc == 0 && wouldBeKoCapture(a, opp) && wouldBeKoCapture(c, opp) &&
          libsAfter(a, pla) <= 2 && libsAfter(c, pla) <= 2 && libertyGainingCaptures(p).isEmpty then return true
        if !near(a).contains(c) then
          if la >= 3 && lc >= 3 then return false
          else if la >= 3 then moves = Vector(a)
          else if lc >= 3 then moves = Vector(c)
        if moves.size == 2 && 2 * lc + connX2(c, pla) > 2 * la + connX2(a, pla) then moves = Vector(c, a)
      val who = if isDef then pla else opp
      var i = 0; var decided = false; var answer = isDef
      while !decided && i < moves.size do
        val m = moves(i)
        if isLegal(m, who) then
          val u = play(m, who); nodes += 1
          val r = try solve(depth + 1) finally undo(u)
          if isDef && !r then { decided = true; answer = false }
          if !isDef && r then { decided = true; answer = true }
        i += 1
      answer
    try solve(0)
    catch case _: Abort => false
    finally ko = savedKo

  /** a chain of two liberties, the attacker first at each: whether either works, and which */
  def attackerFirst(p: Int): (Boolean, Vector[Int]) =
    val pla = b(p); val opp = 3 - pla
    val libs = chain(p)._2
    if libs.size != 2 then return (false, Vector.empty)
    val works = libs.filter { m =>
      isLegal(m, opp) && { val u = play(m, opp); try captured(p, defenderFirst = true) finally undo(u) }
    }
    (works.nonEmpty, works)
