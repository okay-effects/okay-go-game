package okay.go

import okay.business.scala3.*
import okay.go.Rules.{Black, Position, White}

/**
 * GO ON 9 x 9, in okay-ui's words alone: two people at one board, or a person against the model of `Model`. The
 * board is a picture with a button at every legal point — an illegal move cannot be asked for. Against the model,
 * the model's logits go through the same mask: what it wanted, and what it was allowed, are shown.
 */
object Go:
  type App[X] = (Ui + Model)[X]

  enum Mode:
    case Two
    case Against(person: Int)

  def main[R[_]: Has[Ui]: Has[Model]]: Unit ! R =
    menu[Int, R]("Go 9 × 9", "Two players" -> 1, "Play Black against the model" -> 2,
      "Play White against the model" -> 3, "Rules" -> 4).flatMap {
      case 1 => turn[R](Mode.Two, Rules.start, Nil).andThen(defer(main[R]))
      case 2 => turn[R](Mode.Against(Black), Rules.start, Nil).andThen(defer(main[R]))
      case 3 => modelMoves[R](Mode.Against(White), Rules.start).andThen(defer(main[R]))
      case _ => inform[R]("Rules", rules).andThen(defer(main[R]))
    }

  val rules: String = "Black and White take turns to put a stone on an empty point; Black begins. A group of stones " +
    "with no empty point next to it is captured and taken off. A stone may not be put where its group would have no " +
    "liberty (unless it captures), and no move may bring back a position seen before (ko). Instead of a stone you " +
    "may pass; two passes in a row end the game. Then each side counts its stones and the empty points that reach " +
    "only its stones; White adds 6.5 (komi). Capture dead stones before you pass: the board is counted as it stands."

  enum Act:
    case Stone(p: Int)
    case Pass, Resign, Menu

  private def status(pos: Position): String =
    s"${Board.word(pos.toPlay)} to play · move ${pos.moves + 1} · captured by Black ${pos.captured(Black)}, " +
      s"by White ${pos.captured(White)}"

  /** a person's turn: a button at each legal point, Pass, Resign, Menu */
  def turn[R[_]: Has[Ui]: Has[Model]](mode: Mode, pos: Position, said: List[Part]): Unit ! R =
    if pos.over then end[R](mode, pos, None)
    else
      val legal = Rules.legal(pos)
      val points = legal.map(p => Button(Rules.name(p))(Act.Stone(p)).at(s"p$p"))
      val heading = mode match
        case Mode.Two => Part.text(status(pos))
        case Mode.Against(c) => Part.text(s"You play ${Board.word(c)} · " + status(pos))
      show(Screen[Act]("Go")((Board(pos, legal.toSet) +: heading +: said)*)(
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

  /** the model's move: its logits; what it wanted (the highest of all) and what it played (the highest legal one, or
   * a pass) */
  def modelMoves[R[_]: Has[Ui]: Has[Model]](mode: Mode, pos: Position): Unit ! R =
    perform[Model](Model.Logits(pos)).flatMap { logits =>
      val legal = Rules.legal(pos)
      val wanted = logits.indices.maxBy(logits)
      val played = (legal :+ Rules.points.size).maxBy(logits)
      def said(k: Int) = if k == Rules.points.size then "pass" else Rules.name(k)
      val why = if wanted == played then "the same" else Rules.tryPlay(pos, wanted).left.toOption.fold("")(_.text)
      val next = if played == Rules.points.size then Rules.pass(pos) else Rules.play(pos, played)
      val note = Part.details("The model wanted" -> (if wanted == played then said(wanted)
          else s"${said(wanted)} — not allowed: $why"),
        "The model played" -> said(played))
      if next.over then end[R](mode, next, None) else defer(turn[R](mode, next, List(note)))
    }

  /** the end: by two passes — the score by area, each point's owner drawn — or by a resignation */
  def end[R[_]: Has[Ui]](mode: Mode, pos: Position, resigned: Option[Int]): Unit ! R =
    val (black, white) = Rules.score(pos.board)
    val result = resigned.fold(Rules.result(pos.board))(c => s"${Board.word(c)} resigns: ${Board.word(
      Rules.other(c))} wins")
    val parts = Seq(Board(pos, Set.empty, areas = resigned.isEmpty), Part.info(result)) ++
      (if resigned.isEmpty then Seq(Part.details("Black" -> s"$black (stones and the points they surround)",
        "White" -> f"$white%.1f (stones, points, and komi 6.5)")) else Nil)
    show(Screen[Unit]("Game over")(parts*)(Button("Back to the menu", Style.Primary)(())))
