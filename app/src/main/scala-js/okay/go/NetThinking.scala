package okay.go

import okay.business.scala3.*
import okay.go.Rules.{Black, Position}
import okay.go.net.{GpuNet, Inputs, Judgement, Puct}
import okay.scala3.std.Async
import org.scalajs.dom
import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.util.{Failure, Success}

/**
 * THE MODEL IN THE PAGE, A NETWORK: KataGo's g170 b10c128 network on WebGPU (`GpuNet`), searched by PUCT — leaves in
 * batches of 8, one GPU pass a batch — for the board's time (1.5 s on 9 x 9, 2.5 s on 13 x 13, 4 s on 19 x 19), by
 * okay-scala's `Async`; cancelled (Menu), it stops. The network is loaded once, at the first move it has to think
 * of. Without WebGPU, the search that plays games out (`Searcher`), as before.
 */
final class NetThinking(net: () => Future[Option[GpuNet]], random: scala.util.Random = new scala.util.Random()):
  private def millis(size: Int): Double = size match
    case 9 => 1500
    case 13 => 2500
    case _ => 4000

  private def thought(pos: Position, s: Puct): Thought =
    val (black, white) = Rules.score(pos.board)
    val won = if pos.toPlay == Black then black > white else white > black
    if pos.passes == 1 && won then Thought(pos.passIndex, "the board as it stands is won")
    else if s.top.children == null then Thought(pos.passIndex, "nothing read")
    else
      val (move, chance) = s.best
      Thought(move, f"a ${chance * 100}%.0f%% chance to win — ${s.visits} positions read by the network, " +
        s"${pos.name(move)} the best")

  def apply[R[_], Rest[_], A](p: A ! R)(using Split[Model, R, Rest], In[Async, Rest]): A ! Rest =
    handle(p, Handler.General[Model, Rest, A, A](a => pure(a), [X] => (asked: Model[X], k: X => A ! Rest) =>
      asked match
        case Model.Think(pos) => Async.cancellable[Thought, Rest] { answer =>
            var stopped = false
            val began = dom.window.performance.now()
            def spent = dom.window.performance.now() - began
            net().onComplete {
              case Success(Some(gpu)) =>
                val s = Puct(pos, _ => throw IllegalStateException("judged in batches"))
                def step(): Unit = if !stopped then
                  if spent >= millis(pos.size) && s.visits > 0 then { stopped = true; answer(Right(thought(pos, s))) }
                  else
                    val batch = s.gather(8)
                    if batch.isEmpty then dom.window.setTimeout(() => step(), 0)
                    else gpu.run(pos.size, batch.map(l => Inputs.of(l.leaf.pos))).onComplete {
                      case Success(raws) =>
                        batch.zip(raws).foreach((l, r) => s.complete(l, Judgement.of(l.leaf.pos, r)))
                        step()
                      case Failure(e) => stopped = true; answer(Left(e))
                    }
                step()
              case _ =>
                // no WebGPU (or the network not loaded): the search that plays games out, between frames
                val budget = Model.budget(pos.size)
                val s = Searcher(pos, random)
                def part(): Unit = if !stopped then
                  val until = dom.window.performance.now() + 25
                  while dom.window.performance.now() < until && s.playouts < budget.playouts do s.run(8)
                  if s.playouts >= budget.playouts || spent >= budget.millis || s.best.isEmpty then
                    stopped = true; answer(Right(Model.answer(pos, s)))
                  else dom.window.setTimeout(() => part(), 0)
                part()
            }
            () => stopped = true
          }.flatMap(k)))

object NetThinking:
  /** the network of the page, loaded and put on the GPU once, at the first call */
  def network(): () => Future[Option[GpuNet]] =
    lazy val loaded: Future[Option[GpuNet]] =
      GpuNet.load("net/b10c128.bin").flatMap(GpuNet.create).recover { case _ => None }
    () => loaded
