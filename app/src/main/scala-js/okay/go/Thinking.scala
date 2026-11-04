package okay.go

import okay.business.scala3.*
import okay.scala3.std.Async
import org.scalajs.dom

/**
 * THE MODEL IN THE PAGE, by okay-scala's `Async`: the search thought between the page's frames — parts of about 25 ms
 * by `setTimeout`, so the page stays alive — until the board's budget (`Model.budget`: games, or time) is spent; then
 * the wait is answered. Cancelled (Menu pressed on the working screen), it stops at once.
 */
final class Thinking(random: scala.util.Random = new scala.util.Random()):
  def apply[R[_], Rest[_], A](p: A ! R)(using Split[Model, R, Rest], In[Async, Rest]): A ! Rest =
    handle(p, Handler.General[Model, Rest, A, A](a => pure(a), [X] => (asked: Model[X], k: X => A ! Rest) =>
      asked match
        case Model.Think(pos) => Async.cancellable[Thought, Rest] { answer =>
            val budget = Model.budget(pos.size)
            val s = Searcher(pos, random)
            val began = dom.window.performance.now()
            var stopped = false
            def part(): Unit = if !stopped then
              val until = dom.window.performance.now() + 25
              while dom.window.performance.now() < until && s.playouts < budget.playouts do s.run(8)
              val spent = dom.window.performance.now() - began
              if s.playouts >= budget.playouts || spent >= budget.millis || s.best.isEmpty then
                stopped = true; answer(Right(Model.answer(pos, s)))
              else dom.window.setTimeout(() => part(), 0)
            dom.window.setTimeout(() => part(), 0)
            () => stopped = true
          }.flatMap(k)))
