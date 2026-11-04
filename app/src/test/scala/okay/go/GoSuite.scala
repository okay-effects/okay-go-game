package okay.go

import okay.business.scala3.*
import okay.go.Rules.*
import okay.go.Go.App

/** GO: the rules on every board (captures, suicide, ko, the score by area), and the whole application by its
 * buttons — two players, and a person against the model, whose every move is legal */
class GoSuite extends munit.FunSuite:
  /** the application with its world: the model a search of `playouts` games, no game far apart, no address */
  def app(playouts: Int = 200, seed: Int = 1): Step[Unit] = steps(copiedTo(scala.collection.mutable.Buffer.empty[String])(
    Place.fixed()(Tables().client()(Model.searching(new scala.util.Random(seed), playouts)(Go.main[App])))))
  val nine: Geometry = Geometry(9)
  def p(name: String): Int = nine.point(name).get
  /** a 9 x 9 position from moves by name, "pass" a pass */
  def moves(ms: String*): Position = ms.foldLeft(start)((pos, m) => if m == "pass" then pass(pos) else play(pos, p(m)))
  def texts(played: Played[?]): List[String] = played.at.toList.flatMap(_.parts).collect {
    case Part.Text(s) => s
    case Part.Notice(_, s) => s
  }

  test("the boards: 9, 13, 19 — names (no I), star points, komi") {
    assertEquals((nine.name(0), nine.name(80), nine.name(40)), ("A9", "J1", "E5"))
    val big = Geometry(19)
    assertEquals((big.name(0), big.name(360), big.name(180)), ("A19", "T1", "K10"))
    assertEquals(big.stars.size, 9); assertEquals(Geometry(13).stars.size, 5); assertEquals(nine.stars.size, 5)
    assertEquals((nine.komi, Geometry(13).komi, big.komi), (6.5, 7.5, 7.5))
    assertEquals(legal(startOf(19)).size, 361)
  }

  test("a stone with no liberty is captured; the capture counted") {
    val pos = moves("B9", "A9", "A8")
    assertEquals(pos.board(p("A9")), Empty)
    assertEquals(pos.captured(Black), 1)
  }

  test("suicide is not allowed; a taken point; a point off the board") {
    val pos = moves("B9", "E5", "A8")
    assertEquals(tryPlay(pos, p("A9")).left.toOption, Some(Why.Suicide))
    assertEquals(tryPlay(pos, p("B9")).left.toOption, Some(Why.Occupied))
    assertEquals(tryPlay(pos, 81).left.toOption, Some(Why.Outside))
  }

  test("ko: retaking at once would repeat the position, so it is not allowed; later it is") {
    val pos = moves("D5", "F4", "E4", "F6", "E6", "G5", "J1", "E5", "F5")
    assertEquals(pos.board(p("E5")), Empty)
    assertEquals(tryPlay(pos, p("E5")).left.toOption, Some(Why.Repeats))
    val later = moves("D5", "F4", "E4", "F6", "E6", "G5", "J1", "E5", "F5", "A1", "B1")
    assert(tryPlay(later, p("E5")).isRight)
  }

  test("the score by area, the board's komi") {
    assertEquals(score(empty(9)), (0, 6.5))
    val wall = (0 until 9).foldLeft(empty(9))((b, r) => b.updated(r * 9 + 4, Black))
    val half = (0 until 9).foldLeft(wall)((b, r) => b.updated(r * 9 + 5, White))
    assertEquals(score(half), (45, 36 + 6.5))
    assertEquals(result(half), "Black wins by 2.5")
    assertEquals(score(empty(19)), (0, 7.5))
  }

  test("two players on 13 x 13 by buttons: the size chosen, a taken point has no button, two passes end it") {
    def play2(acts: Act*) = Play(app(), acts*)
    val p1 = play2(Act.press("Two players"), Act.press("13 × 13"), Act.press("G7"))
    assert(p1.at.get.buttons.forall(_.label != "G7") && p1.at.get.buttons.exists(_.label == "N13"))
    assert(texts(p1).head.startsWith("White to play"))
    val over = play2(Act.press("Two players"), Act.press("13 × 13"), Act.press("G7"), Act.press("Pass"),
      Act.press("Pass"))
    assertEquals(over.at.map(_.title), Some("Game over"))
    assertEquals(texts(over).head, "Black wins by 161.5")
  }

  test("against the model: it answers with a legal move and says what it thinks; as White it begins") {
    val p1 = Play(app(), Act.press("Play Black against the model"), Act.press("9 × 9 — a quick game"), Act.press("E5"))
    val said = p1.at.get.parts.collect { case Part.Details(rows) => rows.map((k, v) => k -> v.text) }.head.toMap
    assert(said("The model thinks").matches("a \\d+% chance to win — 200 games played out, [A-J]\\d the best"), said)
    assert(nine.point(said("The model played")).isDefined, said)
    val p2 = Play(app(), Act.press("Play White against the model"), Act.press("19 × 19 — the full board"))
    assert(texts(p2).head.startsWith("You play White · White to play · move 2"), texts(p2).toString)
  }

  test("the model passes when the opponent passed and the board as it stands is won") {
    val wall = (0 until 9).foldLeft(empty(9))((b, r) => b.updated(r * 9 + 4, Black).updated(r * 9 + 5, White))
    val won = Position(wall, Black, Set(wall), 1, Map(Black -> 0, White -> 0), None, 40)
    val s = Searcher(won, new scala.util.Random(1)); s.run(200)
    assertEquals(Model.answer(won, s).move, won.passIndex)
  }

  test("the model's games: all its moves legal to the end, on 9 x 9 and 13 x 13") {
    for size <- Seq(9, 13) do
      var pos = startOf(size); val r = new scala.util.Random(3)
      while !pos.over && pos.moves < 400 do
        val s = Searcher(pos, r); s.run(60)
        val t = Model.answer(pos, s)
        pos = if t.move == pos.passIndex then pass(pos) else play(pos, t.move)
      assert(pos.over, s"$size: ${pos.moves} moves")
  }

  test("while the model thinks: a working screen with the board, Menu its way out — the game left, no move made") {
    val thinking = Play(app(), Act.press("Play White against the model"), Act.press("9 × 9 — a quick game"))
    assert(thinking.seen.contains("Go"), thinking.seen.toString)
    val left = Play(app(), Act.press("Play White against the model"), Act.press("9 × 9 — a quick game"),
      Act.leave("Menu"))
    assertEquals(left.at.map(_.title), Some("Go"))
    assert(left.at.get.buttons.exists(_.label == "Two players"), "back at the menu")
  }
