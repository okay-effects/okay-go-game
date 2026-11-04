package okay.go

import okay.business.scala3.*
import okay.go.Rules.{Black, Position, White}

/**
 * GO ON 9 x 9, 13 x 13 OR 19 x 19, in okay-ui's words alone: two people at one board, two far apart (`Online`), or a
 * person against the model of `Model` — a search. The board is a picture with a button at every legal point: an
 * illegal move cannot be asked for. While the model thinks, a working screen shows the board, Menu its way out.
 */
object Go:
  type App[X] = (Ui + Model + Remote + Place + Clipboard)[X]

  enum Mode:
    case Two
    case Against(person: Int)

  /** the page opened by a game's link goes to that game; else the menu */
  def main[R[_]: Has[Ui]: Has[Model]: Has[Remote]: Has[Place]: Has[Clipboard]]: Unit ! R =
    perform[Place](Place.Read()).flatMap { ps =>
      ps.get("g").filter(Room.wellFormed) match
        case Some(g) => Online.join[R](g, ps.get("k").filter(Room.wellFormed), None).andThen(defer(menuOf[R]))
        case None => menuOf[R]
    }

  def menuOf[R[_]: Has[Ui]: Has[Model]: Has[Remote]: Has[Place]: Has[Clipboard]]: Unit ! R =
    menu[Int, R]("Go", "Two players" -> 1, "Play a friend online" -> 5, "Play Black against the model" -> 2,
      "Play White against the model" -> 3, "Rules" -> 4).flatMap {
      case 5 => Online.start[R].andThen(defer(menuOf[R]))
      case 1 => sized[R](s => turn[R](Mode.Two, Rules.startOf(s), Nil)).andThen(defer(menuOf[R]))
      case 2 => sized[R](s => turn[R](Mode.Against(Black), Rules.startOf(s), Nil)).andThen(defer(menuOf[R]))
      case 3 => sized[R](s => modelMoves[R](Mode.Against(White), Rules.startOf(s))).andThen(defer(menuOf[R]))
      case _ => inform[R]("Rules", rules).andThen(defer(menuOf[R]))
    }

  /** the board's size chosen, then `game` of it; nothing when Back */
  def sized[R[_]: Has[Ui]](game: Int => Unit ! R): Unit ! R =
    menu[Int, R]("The board", "9 × 9 — a quick game" -> 9, "13 × 13" -> 13, "19 × 19 — the full board" -> 19,
      "Back" -> 0).flatMap(s => if s == 0 then pure(()) else game(s))

  val rules: String = "Black and White take turns to put a stone on an empty point; Black begins. A group of stones " +
    "with no empty point next to it is captured and taken off. A stone may not be put where its group would have no " +
    "liberty (unless it captures), and no move may bring back a position seen before (ko). Instead of a stone you " +
    "may pass; two passes in a row end the game. Then each side counts its stones and the empty points that reach " +
    "only its stones; White adds komi — 6.5 on 9 × 9, 7.5 on 13 × 13 and 19 × 19. Capture dead stones before you " +
    "pass: the board is counted as it stands."

  enum Act:
    case Stone(p: Int)
    case Pass, Resign, Menu

  private def status(pos: Position): String =
    s"${Board.word(pos.toPlay)} to play · move ${pos.moves + 1} · captured by Black ${pos.captured(Black)}, " +
      s"by White ${pos.captured(White)}"

  private def heading(mode: Mode, pos: Position): Part = mode match
    case Mode.Two => Part.text(status(pos))
    case Mode.Against(c) => Part.text(s"You play ${Board.word(c)} · " + status(pos))

  /** a person's turn: a button at each legal point, Pass, Resign, Menu */
  def turn[R[_]: Has[Ui]: Has[Model]](mode: Mode, pos: Position, said: List[Part]): Unit ! R =
    if pos.over then end[R](mode, pos, None)
    else
      val legal = Rules.legal(pos)
      val points = legal.map(p => Button(pos.name(p))(Act.Stone(p)).at(s"p$p"))
      show(Screen[Act]("Go")((Board(pos, legal.toSet) +: heading(mode, pos) +: said)*)(
        (points ++ Seq(Button("Pass")(Act.Pass), Button("Resign", Style.Danger)(Act.Resign),
          Button("Menu")(Act.Menu)))*)).flatMap {
        case Act.Menu => pure(())
        case Act.Resign => end[R](mode, pos, Some(pos.toPlay))
        case Act.Pass => after[R](mode, Rules.pass(pos))
        case Act.Stone(p) => after[R](mode, Rules.play(pos, p))
      }

  /** after a person's move: the model's turn when it is the model's, else the next person's */
  def after[R[_]: Has[Ui]: Has[Model]](mode: Mode, pos: Position): Unit ! R = mode match
    case Mode.Against(c) if pos.toPlay != c && !pos.over => modelMoves[R](mode, pos)
    case _ => defer(turn[R](mode, pos, Nil))

  /** the model's move: a working screen while it thinks — the board, "The model is thinking…" — with Menu as its way
   * out: pressed, the thinking is cancelled (in a page, its `Async` wait) and the game left */
  def modelMoves[R[_]: Has[Ui]: Has[Model]](mode: Mode, pos: Position): Unit ! R =
    interruptible[Unit, Thought, R](Button("Menu")(()))(
      working[R]("Go")(Board(pos, Set.empty), heading(mode, pos), Part.info("The model is thinking…"))
        .andThen(perform[Model](Model.Think(pos)))).flatMap {
      case Left(_) => pure(())
      case Right(t) => played[R](mode, pos, t)
    }

  /** the model's thought played: its point if legal (a search's always is), else a pass */
  private def played[R[_]: Has[Ui]: Has[Model]](mode: Mode, pos: Position, t: Thought): Unit ! R =
    val legal = t.move != pos.passIndex && Rules.tryPlay(pos, t.move).isRight
    val next = if legal then Rules.play(pos, t.move) else Rules.pass(pos)
    val note = Part.details("The model played" -> (if legal then pos.name(t.move) else "pass"),
      "The model thinks" -> t.note)
    // how the model works, at the bottom: which model, on what, and the last move's numbers (the handler's words)
    val engine = if t.about.isEmpty then Nil else List(Part.section("The engine")(Part.details(t.about*)))
    if next.over then end[R](mode, next, None) else defer(turn[R](mode, next, note :: engine))

  /** the end: by two passes — the score by area, each point's owner drawn — or by a resignation */
  def end[R[_]: Has[Ui]](mode: Mode, pos: Position, resigned: Option[Int]): Unit ! R =
    val (black, white) = Rules.score(pos.board)
    val result = resigned.fold(Rules.result(pos.board))(c => s"${Board.word(c)} resigns: ${Board.word(
      Rules.other(c))} wins")
    val parts = Seq(Board(pos, Set.empty, areas = resigned.isEmpty), Part.info(result)) ++
      (if resigned.isEmpty then Seq(Part.details("Black" -> s"$black (stones and the points they surround)",
        "White" -> f"$white%.1f (stones, points, and komi ${pos.geo.komi}%.1f)")) else Nil)
    show(Screen[Unit]("Game over")(parts*)(Button("Back to the menu", Style.Primary)(())))
