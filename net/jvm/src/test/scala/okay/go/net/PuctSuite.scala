package okay.go.net

import okay.go.Rules

/** THE SEARCH'S BOOKKEEPING, with a judge of its own (every legal move alike, every position even): batches of
 * different leaves, virtual losses taken back, visits counted, the answer a legal move */
class PuctSuite extends munit.FunSuite:
  val even: Rules.Position => Judgement = pos =>
    val legal = Rules.legal(pos) :+ pos.passIndex
    val pr = new Array[Float](pos.passIndex + 1); legal.foreach(pr(_) = 1f / legal.size)
    Judgement(pr, 0.0)

  test("a batch: different leaves; completed, the root's visits are the judgements and no virtual loss is left") {
    val s = Puct(Rules.startOf(9), even)
    s.run(1) // the root judged: its children made
    val batch = s.gather(8)
    assertEquals(batch.size, 8)
    assertEquals(batch.map(_.leaf).distinct.size, 8)
    batch.foreach(l => s.complete(l, even(l.leaf.pos)))
    assertEquals(s.top.n, 9); assertEquals(s.visits, 9)
    val kids = s.top.children.nn.filter(_.n > 0)
    assertEquals(kids.map(_.n).sum, 8)
    assert(kids.forall(k => math.abs(k.w) < 1e-9), "no virtual loss left: every value 0")
  }

  test("run and gather both end with a legal move; a finished game scored, not judged") {
    val s = Puct(Rules.startOf(9), even); s.run(50)
    assert(Rules.tryPlay(Rules.startOf(9), s.best._1).isRight || s.best._1 == 81)
    val over = Rules.pass(Rules.pass(Rules.startOf(9)))
    val t = Puct(over, _ => fail("a finished game is not judged")); t.gather(4)
    assertEquals(t.visits, 4)
  }
