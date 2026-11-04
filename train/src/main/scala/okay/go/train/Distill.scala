package okay.go.train

import mlx.*
import mlx.Mlx.*
import okay.go.net.Binary
import java.nio.file.{Files, Path, StandardCopyOption, StandardOpenOption}

/**
 * THE STUDENT LEARNS FROM THE TEACHER, online: KataGo's b10c128 (nets/b10c128.bin) plays games of 9 x 9, 13 x 13
 * and 19 x 19 (`SelfPlay`), its answers go to replay buffers in memory (`Replay`), and the student — KataGo's
 * layout at a smaller size (`Weights.student`) — learns from random batches of them by AdamW: the cross-entropy to
 * the teacher's policy (with the pass) and value, ownership's and the score's squared error at a small weight.
 * A round: a move for every game of each size, then `reuse` steps of each size's batch (each position seen about
 * `reuse` times). Every `every` steps: the loss and the policy's agreement (argmax) with the teacher on held-out
 * positions, and the student written to nets/own.bin (by `Binary`: the page's format).
 *
 * `sbt "train/runMain okay.go.train.Distill [steps] [blocks] [channels]"`; `--resume=<step>` goes on from nets/own.bin
 * at that step of the schedule (the log says the last step saved).
 */
object Distill:
  final case class Plan(steps: Int = 60000, blocks: Int = 6, channels: Int = 64, pooled: Int = 16, head: Int = 32,
      valueHidden: Int = 64, lr: Float = 1e-3f, warmup: Int = 1000, decay: Float = 1e-4f, reuse: Int = 4,
      resume: Boolean = false, start: Int = 0, out: String = "nets/own.bin")

  /** the sizes played and learned: games at once, a batch */
  val sizes = Vector((9, 256, 256), (13, 128, 128), (19, 64, 64))

  def main(args: Array[String]): Unit =
    val nums = args.filterNot(_.startsWith("--")).map(_.toInt)
    val base = Plan()
    val plan = base.copy(steps = nums.lift(0).getOrElse(base.steps), blocks = nums.lift(1).getOrElse(base.blocks),
      channels = nums.lift(2).getOrElse(base.channels), resume = args.exists(_.startsWith("--resume")),
      start = args.find(_.startsWith("--resume=")).map(_.drop(9).toInt).getOrElse(0))
    run(plan)

  def kindsOf(blocks: Int): Vector[Boolean] = Vector.tabulate(blocks)(i => i % 3 == 2)

  def run(plan: Plan): Unit =
    Mlx.init()
    // MEMORY KEPT SMALL (the Mac shared): MLX keeps at most 1 GB of freed buffers for reuse (its default: all it may)
    Mlx.setCacheLimit(1L << 30)
    val log = Logger()
    val keep = new Scope
    val teacherModel = Binary.read(Files.readAllBytes(Path.of("nets/b10c128.bin")))
    val teacher = MlxNet(Weights.kinds(teacherModel))
    val teacherWeights = MlxNet.arrays(Weights.of(teacherModel))(using keep)
    val kinds = kindsOf(plan.blocks)
    val init =
      val saved = Path.of(plan.out)
      if plan.resume && Files.exists(saved) then Weights.of(Binary.read(Files.readAllBytes(saved)))
      else Weights.student(kinds, plan.channels, plan.pooled, plan.head, plan.valueHidden, scala.util.Random(1))
    val student = MlxNet(kinds)
    var params: Vector[Arr] = MlxNet.arrays(init)(using keep)
    var m: Vector[Arr] = init.map(t => zeros(t.shape, Dtype.Float32)(using keep))
    var v: Vector[Arr] = init.map(t => zeros(t.shape, Dtype.Float32)(using keep))
    val decays = init.map(_.shape.size >= 2)
    log(f"the student: ${plan.blocks} blocks of ${plan.channels} channels (pooling at ${kinds.zipWithIndex
      .filter(_._1).map(_._2).mkString(", ")}), ${init.map(_.data.length.toLong).sum}%,d weights; " +
      s"the teacher: ${teacherModel.parameters} weights; $plan")

    val replays = sizes.map((s, _, _) => s -> Replay(s, if s == 9 then 200000 else if s == 13 then 80000 else 30000))
      .toMap
    val held = sizes.map((s, _, _) => s -> Replay(s, 2048)).toMap
    val plays = sizes.map((s, games, _) => s -> SelfPlay(teacher, teacherWeights, s, games, replays(s), s)).toMap
    val heldPlays = sizes.map((s, games, _) => s -> SelfPlay(teacher, teacherWeights, s, games / 4, held(s), 1000 + s))
      .toMap
    val random = scala.util.Random(5)

    /** the loss of a batch, its parts: policy, value, ownership, score — and the policy's argmax */
    def losses(w: Seq[Arr], b: Replay#Batch, size: Int)(using Scope): (Arr, Arr, Arr, Arr, Arr, Arr) =
      val area = size * size; val n = b.n
      val o = student(w, size, floats(b.spatial, n, 22, area), floats(b.global, n, 19))
      val logits = concatenate(Seq(o.policy, o.pass), 1)
      val logp = subtract(logits, logsumexp(logits, 1, true))
      val tp = floats(b.policy, n, area + 1)
      def mean(x: Arr, count: Int) = divide(sum(reshape(x, x.shape.product), 0), scalar(count.toFloat))
      val lp = negative(mean(multiply(tp, logp), n))
      val logv = subtract(o.value, logsumexp(o.value, 1, true))
      val lv = negative(mean(multiply(floats(b.value, n, 3), logv), n))
      val tanhOwn = subtract(multiply(sigmoid(multiply(o.ownership, scalar(2f))), scalar(2f)), scalar(1f))
      val dOwn = subtract(tanhOwn, floats(b.own, n, area))
      val lo = mean(multiply(dOwn, dOwn), n * area)
      val dScore = subtract(reshape(slice(o.score, Seq(0, 0), Seq(n, 1)), n), floats(b.score, n))
      val ls = mean(multiply(dScore, dScore), n)
      val total = add(add(lp, lv), add(multiply(lo, scalar(0.5f)), multiply(ls, scalar(0.5f))))
      (total, lp, lv, lo, ls, argmax(logits, 1))

    // the step the schedule is at (resumed: where the run stopped); AdamW's own count from 0, its moments new — a
    // resumed run warms up again over 500 steps
    var step = plan.start
    var adam = 0
    def lr(t: Int): Float =
      if t < plan.warmup then plan.lr * (t + 1) / plan.warmup
      else
        val f = (t - plan.warmup).toDouble / math.max(1, plan.steps - plan.warmup)
        (plan.lr * (0.05 + 0.95 * 0.5 * (1 + math.cos(math.Pi * math.min(1.0, f))))).toFloat

    def train(size: Int, batch: Int): Float =
      val s = new Scope
      given Scope = s
      try
        val b = replays(size).batch(batch, random)
        val (value, grads) = valueAndGrad(params, params.indices)(w => losses(w, b, size)._1)
        step += 1; adam += 1
        val (b1, b2, eps) = (0.9f, 0.999f, 1e-8f)
        val c1 = 1f - math.pow(b1, adam).toFloat; val c2 = 1f - math.pow(b2, adam).toFloat
        val rate = if plan.start > 0 then lr(step) * math.min(1f, adam / 500f) else lr(step)
        val next = params.indices.map { k =>
          val g = grads(k)
          val mk = add(multiply(m(k), scalar(b1)), multiply(g, scalar(1 - b1)))
          val vk = add(multiply(v(k), scalar(b2)), multiply(multiply(g, g), scalar(1 - b2)))
          val upd = divide(divide(mk, scalar(c1)), add(sqrt(divide(vk, scalar(c2))), scalar(eps)))
          val withDecay = if decays(k) then add(upd, multiply(params(k), scalar(plan.decay))) else upd
          (subtract(params(k), multiply(withDecay, scalar(rate))), mk, vk)
        }
        evalAll(next.flatMap((p, a, c) => Seq(p, a, c)))
        val loss = value.itemFloat
        val old = params ++ m ++ v
        params = next.map(_._1.moveTo(keep)).toVector
        m = next.map(_._2.moveTo(keep)).toVector
        v = next.map(_._3.moveTo(keep)).toVector
        old.foreach(keep.release)
        loss
      finally s.close()

    def evaluate(): String =
      sizes.map { (size, _, _) =>
        val h = held(size)
        val s = new Scope
        given Scope = s
        try
          val b = h.of(Vector.range(0, h.count).take(1024))
          val (t, lp, lv, lo, ls, guess) = losses(params, b, size)
          val g = guess.toInts; val area1 = size * size + 1
          val best = (j: Int) => (0 until area1).maxBy(i => b.policy(j * area1 + i))
          val agree = g.indices.count(j => g(j) == best(j)).toFloat / g.length
          f"$size: loss ${t.itemFloat}%.3f (policy ${lp.itemFloat}%.3f value ${lv.itemFloat}%.3f own " +
            f"${lo.itemFloat}%.3f score ${ls.itemFloat}%.3f) agree ${agree * 100}%.1f%%"
        finally s.close()
      }.mkString("; ")

    def save(): Unit =
      val s = new Scope
      try
        val ts = params.map(a => Tensor(a.toFloats, a.shape))
        val bytes = Binary.write(Weights.model("own", kinds, ts))
        val tmp = Path.of(plan.out + ".tmp")
        Files.write(tmp, bytes)
        Files.move(tmp, Path.of(plan.out), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
      finally s.close()

    // the held-out positions: games of their own, not learned from
    while held.values.exists(h => h.count < 1024) do heldPlays.values.foreach(p => p.step())
    log("held-out positions: " + held.values.map(h => s"${h.size}: ${h.count}").mkString(", "))
    val began = System.nanoTime()
    var gen = 0.0; var learn = 0.0
    var running = 0.0; var counted = 0
    val every = 1000
    while step < plan.steps do
      val t0 = System.nanoTime()
      plays.values.foreach(_.step())
      val t1 = System.nanoTime()
      gen += (t1 - t0) / 1e9
      if replays.values.forall(r => r.count >= 4096) then
        for (size, games, batch) <- sizes; _ <- 0 until plan.reuse * games / batch do
          running += train(size, batch); counted += 1
          if step % every == 0 then
            val mins = (System.nanoTime() - began) / 6e10
            log(f"step $step lr ${lr(step)}%.2e train loss ${running / counted}%.3f; held out: ${evaluate()}; " +
              f"positions ${replays.values.map(_.added).sum}%,d, games ${plays.values.map(_.finished).sum}%,d; " +
              f"$mins%.1f min (playing ${gen / 60}%.1f, learning ${learn / 60}%.1f); " +
              f"MLX ${Mlx.activeMemory / 1e9}%.1f GB, cached ${Mlx.cacheMemory / 1e9}%.1f GB, peak " +
              f"${Mlx.peakMemory / 1e9}%.1f GB")
            running = 0; counted = 0
            save()
        learn += (System.nanoTime() - t1) / 1e9
    save()
    log("done: " + evaluate())

  /** the log: the console and train/target/distill.log */
  final class Logger:
    private val file = Path.of("train/target/distill.log")
    Files.createDirectories(file.getParent)
    def apply(line: String): Unit =
      val l = s"${java.time.LocalTime.now().withNano(0)} $line"
      println(l)
      Files.writeString(file, l + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
