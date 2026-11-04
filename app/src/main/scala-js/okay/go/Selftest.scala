package okay.go

import okay.go.net.{Forward, GpuNet, Inputs, Raw}
import okay.go.Rules.{Position, pass, play, startOf}
import org.scalajs.dom
import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

/**
 * THE PAGE'S SELF-TEST (`?selftest`): the network on WebGPU against the CPU reference in the same page — positions of
 * 9 x 9, 13 x 13 and 19 x 19 (games at random by the rules), a batch on the GPU, one by one on the CPU; the largest
 * difference of the policy's logits, the pass and the value, and the time each takes; written into the page (#selftest)
 */
object Selftest:
  def game(size: Int, length: Int, seed: Int): Position =
    val r = new scala.util.Random(seed)
    var pos = startOf(size)
    while pos.moves < length && !pos.over do
      val moves = Rules.legal(pos).filterNot(p => pos.geo.near(p).forall(pos.board(_) == pos.toPlay))
      pos = if moves.isEmpty || r.nextInt(30) == 0 then pass(pos) else play(pos, moves(r.nextInt(moves.size)))
    pos

  def run(net: String): Unit =
    val out = dom.document.createElement("pre"); out.id = "selftest"
    dom.document.body.appendChild(out)
    def say(s: String): Unit = { out.textContent = out.textContent + s + "\n"; println(s) }
    val now = () => dom.window.performance.now()
    (for
      (model, _) <- GpuNet.load(s"net/$net.bin")
      gpu <- GpuNet.create(model)
      _ = say(s"model $net: ${model.parameters} parameters; WebGPU: ${gpu.fold(identity, _ => "yes")}")
      _ <- gpu.fold(_ => Future.unit, net =>
        val cpu = Forward(model)
        Seq(9, 13, 19).foldLeft(Future.unit) { (prev, size) => prev.flatMap { _ =>
          val positions = (0 until 8).toVector.map(k => game(size, k * size * size / 16, 100 + k))
          val inputs = positions.map(Inputs.of)
          for
            _ <- net.run(size, inputs) // warm: pipelines, buffers
            t0 = now()
            raws <- net.run(size, inputs)
            gpuMs = now() - t0
          yield
            val t1 = now()
            val refs = inputs.map((s, g) => cpu.run(size, s, g))
            val cpuMs = (now() - t1) / inputs.size
            def diff(a: Raw, b: Raw): Double =
              val dp = a.policy.indices.map(i => math.abs(a.policy(i) - b.policy(i))).max
              dp.max(math.abs(a.pass - b.pass)).max(a.value.indices.map(i => math.abs(a.value(i) - b.value(i))).max)
            val worst = raws.zip(refs).map(diff).max
            say(f"$size x $size: largest difference $worst%.2e; GPU a batch of 8 in $gpuMs%.1f ms; CPU $cpuMs%.1f ms " +
              "a position")
        }}
      )
    yield say("selftest: done")).failed.foreach(e => say(s"selftest: failed: $e"))
