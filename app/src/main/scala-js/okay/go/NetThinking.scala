package okay.go

import okay.business.scala3.*
import okay.go.Rules.{Black, Position}
import okay.go.net.{GpuNet, Inputs, Judgement, Model as Net, Pooling, Puct}
import okay.scala3.std.Async
import org.scalajs.dom
import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.util.{Failure, Success}

/** the network as the page has it: on the GPU, the model it is, how many bytes it came in and how long it took */
final class Engine(val gpu: GpuNet, val model: Net, val bytes: Int, val loadMs: Double)

/**
 * THE MODEL IN THE PAGE, A NETWORK: KataGo's g170 b10c128 network on WebGPU (`GpuNet`), searched by PUCT — leaves in
 * batches of 8, one GPU pass a batch — for the board's time (1.5 s on 9 x 9, 2.5 s on 13 x 13, 4 s on 19 x 19), by
 * okay-scala's `Async`; cancelled (Menu), it stops. The network is loaded once, at the first move it has to think
 * of. Without WebGPU, the search that plays games out (`Searcher`), as before. Each thought says how the model works
 * (`Thought.about`): the model, the device, the memory, and the move's numbers — where its time went.
 */
final class NetThinking(net: () => Future[Option[Engine]], random: scala.util.Random = new scala.util.Random()):
  private def millis(size: Int): Double = size match
    case 9 => 1500
    case 13 => 2500
    case _ => 4000
  /** the batch: 32 on 9 x 9, 16 on the larger boards — with the tiled convolution the best of those tried (8, 16, 32)
   * in Chromium on Apple Metal 3 */
  private def batchOf(size: Int): Int = if size <= 9 then 32 else 16
  private def mb(bytes: Long) = f"${bytes / 1048576.0}%.1f MB"

  /** what the network's search did for one move */
  private final class Clock:
    var batches = 0; var gpuMs = 0.0; var inputsMs = 0.0; var treeMs = 0.0

  private def about(e: Engine, pos: Position, s: Puct, c: Clock, spentMs: Double): List[(String, String)] =
    val m = e.model
    val pooling = m.blocks.count(_.isInstanceOf[Pooling])
    val best = s.top.children.nn.map(_.n).maxOption.getOrElse(0)
    def per(ms: Double) = if c.batches == 0 then "—" else f"${ms / c.batches}%.1f ms"
    List(
      "Model" -> (f"KataGo g170 b10c128 — ${m.blocks.size} residual blocks of ${m.conv1.out} channels " +
        f"($pooling with global pooling), ${m.parameters}%,d weights; CC0"),
      "Runs on" -> s"WebGPU — ${e.gpu.adapter} (kernels in f32${if e.gpu.f16 then "; f16 available" else ""})",
      "Weights" -> f"${mb(e.bytes)}, float32, loaded in ${e.loadMs / 1000}%.1f s",
      "GPU memory" -> (s"${mb(e.gpu.weightBytes + e.gpu.otherBytes)} (weights ${mb(e.gpu.weightBytes)}, " +
        s"activations and the rest ${mb(e.gpu.otherBytes)})"),
      "This move" -> (f"${s.visits} positions read in ${spentMs / 1000}%.1f s — " +
        f"${s.visits * 1000 / math.max(1.0, spentMs)}%.0f a second; ${c.batches} batches of ${batchOf(pos.size)}"),
      "A batch" -> (s"the network on the GPU ${per(c.gpuMs)} (sent to read back), its inputs on the CPU " +
        s"${per(c.inputsMs)}, the tree ${per(c.treeMs)} — the CPU's part made while the GPU reads the batch before"),
      "Search" -> (f"PUCT (c 1.1, first-play reduction 0.2), ${millis(pos.size) / 1000}%.1f s a move on " +
        f"${pos.size} × ${pos.size}; the move played read $best times"))

  private def thought(pos: Position, s: Puct, about: List[(String, String)]): Thought =
    val (black, white) = Rules.score(pos.board)
    val won = if pos.toPlay == Black then black > white else white > black
    if pos.passes == 1 && won then Thought(pos.passIndex, "the board as it stands is won", about)
    else if s.top.children == null then Thought(pos.passIndex, "nothing read", about)
    else
      val (move, chance) = s.best
      Thought(move, f"a ${chance * 100}%.0f%% chance to win — ${s.visits} positions read by the network, " +
        s"${pos.name(move)} the best", about)

  def apply[R[_], Rest[_], A](p: A ! R)(using Split[Model, R, Rest], In[Async, Rest]): A ! Rest =
    handle(p, Handler.General[Model, Rest, A, A](a => pure(a), [X] => (asked: Model[X], k: X => A ! Rest) =>
      asked match
        case Model.Think(pos) => Async.cancellable[Thought, Rest] { answer =>
            var stopped = false
            val began = dom.window.performance.now()
            def now = dom.window.performance.now()
            def spent = now - began
            net().onComplete {
              case Success(Some(e)) =>
                val s = Puct(pos, _ => throw IllegalStateException("judged in batches"))
                val c = Clock()
                // A PIPELINE: while the GPU reads one batch, the CPU gathers and prepares the next (its leaves chosen
                // with the first's virtual losses on); each leaf scanned once, for its inputs and its judgement
                final class Ready(val leaves: Vector[s.Leaf], val scans: Vector[okay.go.Scan],
                    val inputs: Vector[(Array[Float], Array[Float])])
                def prepare(): Ready =
                  val t0 = now
                  val leaves = s.gather(batchOf(pos.size))
                  val t1 = now
                  val scans = leaves.map(l => okay.go.Scan(l.leaf.pos))
                  val inputs = scans.map(Inputs.of)
                  c.treeMs += t1 - t0; c.inputsMs += now - t1
                  Ready(leaves, scans, inputs)
                var next: Option[Ready] = None
                def finish(): Unit = { stopped = true; answer(Right(thought(pos, s, about(e, pos, s, c, spent)))) }
                def launch(r: Ready): Unit =
                  if stopped then ()
                  else if r.leaves.isEmpty then
                    if spent >= millis(pos.size) && s.visits > 0 then finish()
                    else dom.window.setTimeout(() => launch(prepare()), 0)
                  else
                    val sent = now
                    e.gpu.run(pos.size, r.inputs).onComplete {
                      case Success(raws) =>
                        c.gpuMs += now - sent; c.batches += 1
                        val t = now
                        r.leaves.indices.foreach(i => s.complete(r.leaves(i), Judgement.of(r.scans(i), raws(i))))
                        c.treeMs += now - t
                        if spent >= millis(pos.size) then finish()
                        else { val ready = next.getOrElse(prepare()); next = None; launch(ready) }
                      case Failure(err) => stopped = true; answer(Left(err))
                    }
                    // the GPU busy: the next batch made meanwhile
                    if next.isEmpty && spent < millis(pos.size) then next = Some(prepare())
                launch(prepare())
              case _ =>
                // no WebGPU (or the network not loaded): the search that plays games out, between frames
                val budget = Model.budget(pos.size)
                val s = Searcher(pos, random)
                def part(): Unit = if !stopped then
                  val until = dom.window.performance.now() + 25
                  while dom.window.performance.now() < until && s.playouts < budget.playouts do s.run(8)
                  if s.playouts >= budget.playouts || spent >= budget.millis || s.best.isEmpty then
                    stopped = true
                    val t = Model.answer(pos, s)
                    answer(Right(t.copy(about = List(
                      "Model" -> "Monte-Carlo tree search with RAVE, heavy playouts and prior knowledge — no network",
                      "Runs on" -> "the CPU: this browser has no WebGPU (or the network did not load)",
                      "This move" -> f"${s.playouts} games played out in ${spent / 1000}%.1f s"))))
                  else dom.window.setTimeout(() => part(), 0)
                part()
            }
            () => stopped = true
          }.flatMap(k)))

object NetThinking:
  /** the network of the page, loaded and put on the GPU once, at the first call */
  def network(): () => Future[Option[Engine]] =
    lazy val loaded: Future[Option[Engine]] =
      val t0 = dom.window.performance.now()
      (for
        (model, bytes) <- GpuNet.load("net/b10c128.bin")
        gpu <- GpuNet.create(model)
      yield gpu.map(g => Engine(g, model, bytes, dom.window.performance.now() - t0))).recover { case _ => None }
    () => loaded
