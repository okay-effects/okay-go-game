package okay.go.train

import mlx.*
import mlx.Mlx.*
import okay.go.{Rules, Scan}
import okay.go.Rules.{Black, Position}
import okay.go.net.{Binary, Inputs, Judgement, Puct, Raw}
import java.nio.file.{Files, Path}

/**
 * TWO NETWORKS PLAY, each searched by PUCT (the page's search) with a number of visits of its own — the networks in
 * MLX, the leaves of all the games at a side's turn judged in one batch. `games` openings of two random moves each,
 * each played twice, the colours swapped; a game ends at two passes (a side passes when the board as it stands is
 * won and the other has passed, as the page) or at 2.5 x the points; scored by area with komi.
 *
 * `sbt "train/runMain okay.go.train.Match <a.bin> <visits> <b.bin> <visits> [size] [openings]"`
 */
object Match:
  final class Side(val name: String, val net: MlxNet, val weights: Seq[Arr], val visits: Int)

  def main(args: Array[String]): Unit =
    Mlx.init()
    val keep = new Scope
    def side(file: String, visits: Int) =
      val m = Binary.read(Files.readAllBytes(Path.of(file)))
      Side(s"$file@$visits", MlxNet(Weights.kinds(m)), MlxNet.arrays(Weights.of(m))(using keep), visits)
    val a = side(args(0), args(1).toInt); val b = side(args(2), args(3).toInt)
    val size = args.lift(4).map(_.toInt).getOrElse(9); val openings = args.lift(5).map(_.toInt).getOrElse(32)
    val (aWins, played, secs) = play(a, b, size, openings)
    println(f"${a.name} against ${b.name} on $size x $size: $aWins of $played won (${aWins * 100.0 / played}%.0f%%) " +
      f"in $secs%.0f s")

  def judgeAll(side: Side, size: Int, scans: Vector[Scan])(using Scope): Vector[Judgement] =
    if scans.isEmpty then Vector.empty
    else
      val area = size * size; val n = scans.size
      val ins = scans.map(Inputs.of)
      val o = side.net(side.weights, size, floats(ins.flatMap(_._1).toArray, n, 22, area),
        floats(ins.flatMap(_._2).toArray, n, 19))
      val (p, ps, v, sc, own) = (o.policy.toFloats, o.pass.toFloats, o.value.toFloats, o.score.toFloats,
        o.ownership.toFloats)
      Vector.tabulate(n) { k =>
        Judgement.of(scans(k), Raw(p.slice(k * area, (k + 1) * area), ps(k), v.slice(k * 3, k * 3 + 3),
          sc.slice(k * 4, k * 4 + 4), own.slice(k * area, (k + 1) * area)))
      }

  /** the games: how many `a` won, of how many, in how many seconds */
  def play(a: Side, b: Side, size: Int, openings: Int): (Int, Int, Double) =
    val began = System.nanoTime()
    val r = scala.util.Random(11)
    val starts = Vector.fill(openings) {
      var p = Rules.startOf(size)
      for _ <- 0 until 2 do { val l = Scan(p).legal; p = Rules.play(p, l(r.nextInt(l.size))) }
      p
    }
    // game k: opening k / 2; a plays black in even games (after the opening's two moves black is to play)
    val games = Array.tabulate(2 * openings)(k => starts(k / 2))
    def aBlack(k: Int) = k % 2 == 0
    val over = Array.fill(games.length)(false)
    def ended(p: Position) = p.passes >= 2 || p.moves >= size * size * 5 / 2
    while !over.forall(identity) do
      for (side, isA) <- Seq((a, true), (b, false)) do
        val mine = games.indices.filter(k => !over(k) && ((games(k).toPlay == Black) == (aBlack(k) == isA)))
        val searches = mine.map(k => k -> Puct(games(k), _ => throw IllegalStateException("in batches"))).toMap
        while searches.values.exists(_.visits < side.visits) do
          val leaves = mine.flatMap { k =>
            val s = searches(k)
            if s.visits < side.visits then s.gather(math.min(16, side.visits - s.visits))
              .map(l => (l.leaf.pos, (j: Judgement) => s.complete(l, j)))
            else Vector.empty
          }
          val s = new Scope
          try
            val js = judgeAll(side, size, leaves.map((pos, _) => Scan(pos)).toVector)(using s)
            leaves.zip(js).foreach { case ((_, done), j) => done(j) }
          finally s.close()
        for k <- mine do
          val p = games(k); val search = searches(k)
          val (black, white) = Rules.score(p.board)
          val won = if p.toPlay == Black then black > white else white > black
          val move = if p.passes == 1 && won then p.passIndex else search.best._1
          games(k) = if move == p.passIndex then Rules.pass(p) else Rules.play(p, move)
          if ended(games(k)) then over(k) = true
    val aWins = games.indices.count { k =>
      val (black, white) = Rules.score(games(k).board)
      (black > white) == aBlack(k)
    }
    (aWins, games.length, (System.nanoTime() - began) / 1e9)
