package okay.go.train

import mlx.*
import mlx.Mlx.*
import okay.go.{Rules, Scan}
import okay.go.Rules.Position
import okay.go.net.Inputs
import java.util.stream.IntStream

/**
 * THE TEACHER'S GAMES: `count` games of one size at once, a move each for all of them per `step` — one batch through
 * the teacher in MLX. Each position goes to the replay buffer with the teacher's answers; the move is drawn from the
 * teacher's policy over the legal moves and the pass: at temperature 1 for the first tenth of the board's points'
 * worth of moves, then sharper (0.3); a game starts with 0–3 random moves. A game ends at two passes or at 2.5 x
 * the board's points of moves, and a new one starts. The CPU's part (scans, inputs, targets) in parallel.
 */
final class SelfPlay(teacher: MlxNet, weights: Seq[Arr], val size: Int, count: Int, replay: Replay, seed: Long):
  private val area = size * size
  private val randoms = Array.tabulate(count)(k => scala.util.Random(seed * 7919 + k))
  private val games = Array.fill(count)(Rules.startOf(size))
  private val opening = Array.fill(count)(0)
  var finished = 0L
  for k <- 0 until count do restart(k)

  private def restart(k: Int): Unit =
    var p = Rules.startOf(size)
    for _ <- 0 until randoms(k).nextInt(4) do
      val legal = Scan(p).legal
      p = Rules.play(p, legal(randoms(k).nextInt(legal.size)))
    games(k) = p; opening(k) = p.moves

  private def par(f: Int => Unit): Unit = IntStream.range(0, count).parallel().forEach(k => f(k))

  def step(): Unit =
    val scans = new Array[Scan](count)
    val inputs = new Array[(Array[Float], Array[Float])](count)
    par { k => scans(k) = Scan(games(k)); inputs(k) = Inputs.of(scans(k)) }
    val s = new Scope
    given Scope = s
    val (policy, pass, value, score, own) =
      try
        val spatial = new Array[Float](count * 22 * area); val global = new Array[Float](count * 19)
        for k <- 0 until count do
          System.arraycopy(inputs(k)._1, 0, spatial, k * 22 * area, 22 * area)
          System.arraycopy(inputs(k)._2, 0, global, k * 19, 19)
        val o = teacher(weights, size, floats(spatial, count, 22, area), floats(global, count, 19))
        (o.policy.toFloats, o.pass.toFloats, o.value.toFloats, o.score.toFloats, o.ownership.toFloats)
      finally s.close()
    val targets = new Array[(Array[Float], Array[Float], Array[Float])](count)
    val moves = new Array[Int](count)
    par { k =>
      val legal = scans(k).legal
      // the policy over the legal moves and the pass, softmax of the teacher's logits
      val p = new Array[Float](area + 1)
      var top = pass(k)
      for m <- legal do top = math.max(top, policy(k * area + m))
      var z = 0.0
      for m <- legal do { val e = math.exp(policy(k * area + m) - top).toFloat; p(m) = e; z += e }
      p(area) = math.exp(pass(k) - top).toFloat; z += p(area)
      for i <- p.indices do p(i) = (p(i) / z).toFloat
      val vt = value.slice(k * 3, k * 3 + 3); val vm = vt.max
      val ve = vt.map(x => math.exp(x - vm).toFloat); val vz = ve.sum
      val o = Array.tabulate(area)(i => math.tanh(own(k * area + i)).toFloat)
      targets(k) = (p, ve.map(_ / vz), o)
      // the move: by temperature
      val g = games(k)
      val t = if g.moves - opening(k) < area / 10 then 1.0 else 0.3
      val w = p.map(x => math.pow(x, 1 / t))
      var u = randoms(k).nextDouble() * w.sum; var i = 0
      while i < area && u >= w(i) do { u -= w(i); i += 1 }
      moves(k) = i
    }
    for k <- 0 until count do
      replay.add(inputs(k)._1, inputs(k)._2, targets(k)._1, targets(k)._2, score(k * 4), targets(k)._3)
    par { k =>
      val g = games(k)
      val next = if moves(k) == area then Rules.pass(g) else Rules.play(g, moves(k))
      if next.passes >= 2 || next.moves >= area * 5 / 2 then { restart(k); synchronized { finished += 1 } }
      else games(k) = next
    }
