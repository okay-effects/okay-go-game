package okay.go

import okay.business.scala3.*
import okay.go.Rules.*
import okay.go.Go.App

/** GO: the rules (captures, suicide, ko, the score by area), and the whole application by its buttons — two players,
 * and a person against the toy model, whose every move is legal */
class GoSuite extends munit.FunSuite:
  def p(name: String): Int = points.find(Rules.name(_) == name).get
  /** a position from moves by name, "pass" a pass */
  def moves(ms: String*): Position = ms.foldLeft(start)((pos, m) => if m == "pass" then pass(pos) else play(pos, p(m)))

  test("names: A9 the top left, J1 the bottom right, no I") {
    assertEquals(Rules.name(0), "A9"); assertEquals(Rules.name(80), "J1"); assertEquals(Rules.name(40), "E5")
  }

  test("a stone with no liberty is captured; the capture counted") {
    // White A9 surrounded by Black B9 and A8
    val pos = moves("B9", "A9", "A8")
    assertEquals(pos.board(p("A9")), Empty)
    assertEquals(pos.captured(Black), 1)
  }

  test("suicide is not allowed; a move that captures is") {
    val pos = moves("B9", "E5", "A8") // White to play: A9 would have no liberty
    assertEquals(tryPlay(pos, p("A9")).left.toOption, Some(Why.Suicide))
    assert(!legal(pos).contains(p("A9")))
    assertEquals(tryPlay(pos, p("B9")).left.toOption, Some(Why.Occupied))
  }

  test("ko: retaking at once would repeat the position, so it is not allowed") {
    // Black: D5 E6 E4 F5... a ko at E5/F5: Black D5, E4, E6; White F4, F6, G5, then White E5 is captured by Black F5
    val pos = moves("D5", "F4", "E4", "F6", "E6", "G5", "J1", "E5", "F5")
    assertEquals(pos.board(p("E5")), Empty, "Black F5 took E5")
    assertEquals(tryPlay(pos, p("E5")).left.toOption, Some(Why.Repeats))
    // after a move elsewhere and a reply, it may be retaken
    val later = moves("D5", "F4", "E4", "F6", "E6", "G5", "J1", "E5", "F5", "A1", "B1")
    assert(tryPlay(later, p("E5")).isRight)
  }

  test("the score by area: an empty board, then a wall") {
    assertEquals(score(empty), (0, komi))
    // a black wall on column E: Black owns A..E (45), White nothing; Black wins
    val wall = (0 until N).foldLeft(empty)((b, r) => b.updated(r * N + 4, Black))
    assertEquals(score(wall), (81, komi))
    val half = (0 until N).foldLeft(wall)((b, r) => b.updated(r * N + 5, White))
    assertEquals(score(half), (45, 36 + komi))
    assertEquals(result(half), "Black wins by 2.5")
  }

  test("two players by buttons: stones placed, a taken point has no button, two passes end the game") {
    def play2(acts: Act*) = Play(steps(Model.toy(new scala.util.Random(1))(Go.main[App])), acts*)
    val p1 = play2(Act.press("Two players"), Act.press("E5"))
    assert(p1.at.get.buttons.forall(_.label != "E5"), "E5 taken: no button")
    assert(p1.at.get.parts.collect { case Part.Text(s) => s }.head.startsWith("White to play"))
    val over = play2(Act.press("Two players"), Act.press("E5"), Act.press("Pass"), Act.press("Pass"))
    assertEquals(over.at.map(_.title), Some("Game over"))
    val info = over.at.get.parts.collect { case Part.Notice(_, s) => s }
    assertEquals(info, List("Black wins by 74.5"))
  }

  test("against the toy: it answers each move with a legal one, says what it wanted, and the game can end") {
    val toy = Model.toy(new scala.util.Random(5))
    def run(acts: Act*) = Play(steps(toy(Go.main[App])), acts*)
    val p1 = run(Act.press("Play Black against the model"), Act.press("E5"))
    val said = p1.at.get.parts.collect { case Part.Details(rows) => rows.map((k, v) => k -> v.text) }.head
    assertEquals(said.map(_._1), List("The model wanted", "The model played"), said.toString)
    val pic = p1.at.get.parts.collect { case Part.Block("go", _, ps) => ps.collect { case Part.Picture(_, _, _, t) =>
      t } }.flatten.head
    assertEquals(pic.split(", ").length, 2, pic)
    // the model's moves through a long game are all legal: Rules.play throws on an illegal one
    var pos = start; val r = new scala.util.Random(9)
    for _ <- 1 to 150 if !pos.over do
      val logits = Model.logits(pos, () => r.nextFloat())
      val k = (legal(pos) :+ points.size).maxBy(logits)
      pos = if k == points.size then pass(pos) else play(pos, k)
    assert(pos.moves > 20, s"${pos.moves} moves")
  }

  test("as White against the model: the model moves first") {
    val p1 = Play(steps(Model.toy(new scala.util.Random(2))(Go.main[App])), Act.press("Play White against the model"))
    assert(p1.at.get.parts.collect { case Part.Text(s) => s }.head.startsWith("You play White · White to play"))
  }
