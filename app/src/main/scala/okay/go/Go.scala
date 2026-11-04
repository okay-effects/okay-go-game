package okay.go

import okay.business.scala3.*
import okay.go.Room.Action
import okay.go.Rules.{Black, Position, White}

/**
 * GO ON 9 x 9, 13 x 13 OR 19 x 19, in okay-ui's words alone: two people at one board, two far apart (`Online`), or a
 * person against the model of `Model` — a search. The board is a picture with a button at every legal point: an
 * illegal move cannot be asked for. While the model thinks, a working screen shows the board, Menu its way out.
 *
 * EVERY GAME KEPT: a game at this screen is made on the games' server too (`Remote`), this page holding both seats'
 * keys, and every move is sent there with the key of the seat that made it; its link (the page's address:
 * #g=…&k=… and &m=… the model's seat, or &o=… the other person's) opens it again — here or on another device, from
 * the moves the server keeps — and the menu lists this browser's games (`Kept`).
 */
object Go:
  type App[X] = (Ui + Model + Remote + Place + Clipboard + Settings)[X]

  enum Mode:
    case Two
    case Against(person: Int)

  /** a game's seats' keys on the server */
  final case class Keys(game: String, of: Map[Int, String]):
    /** its link's parameters: the person's (or Black's) key, and the model's or the other person's */
    def link(mode: Mode): Map[String, String] = mode match
      case Mode.Two => Map("g" -> game, "k" -> of(Black), "o" -> of(White))
      case Mode.Against(c) => Map("g" -> game, "k" -> of(c), "m" -> of(Rules.other(c)))

  /** a game at this screen: how it is played, and its keys when the server keeps it (none: kept nowhere) */
  final case class Table(mode: Mode, keys: Option[Keys])

  /** the page opened by a game's link goes to that game; else the menu */
  def main[R[_]: Has[Ui]: Has[Model]: Has[Remote]: Has[Place]: Has[Clipboard]: Has[Settings]]: Unit ! R =
    perform[Place](Place.Read()).flatMap(ps => open[R](ps).andThen(defer(menuOf[R])))

  /** the game of a link's parameters: kept here (the model's or the other person's key with it) or far apart */
  def open[R[_]: Has[Ui]: Has[Model]: Has[Remote]: Has[Place]: Has[Clipboard]: Has[Settings]](
      ps: Map[String, String]): Unit ! R =
    def key(k: String) = ps.get(k).filter(Room.wellFormed)
    ps.get("g").filter(Room.wellFormed) match
      case None => pure(())
      case Some(g) => (key("k"), key("m").orElse(key("o"))) match
        case (Some(k), Some(other)) => resume[R](g, k, other, model = key("m").isDefined)
        case _ => Online.join[R](g, key("k"), None)

  def menuOf[R[_]: Has[Ui]: Has[Model]: Has[Remote]: Has[Place]: Has[Clipboard]: Has[Settings]]: Unit ! R =
    Kept.all[R].flatMap { kept =>
      val again = if kept.isEmpty then Nil else List("Continue a game" -> 6)
      menu[Int, R]("Go", (again ++ List("Two players" -> 1, "Play a friend online" -> 5,
        "Play Black against the model" -> 2, "Play White against the model" -> 3, "Rules" -> 4))*).flatMap {
        case 6 => games[R](kept).andThen(defer(menuOf[R]))
        case 5 => Online.start[R].andThen(defer(menuOf[R]))
        case 1 => sized[R](s => fresh[R](Mode.Two, s)).andThen(defer(menuOf[R]))
        case 2 => sized[R](s => fresh[R](Mode.Against(Black), s)).andThen(defer(menuOf[R]))
        case 3 => sized[R](s => fresh[R](Mode.Against(White), s)).andThen(defer(menuOf[R]))
        case _ => inform[R]("Rules", rules).andThen(defer(menuOf[R]))
      }
    }

  /** this browser's games, the latest first: one chosen goes on */
  def games[R[_]: Has[Ui]: Has[Model]: Has[Remote]: Has[Place]: Has[Clipboard]: Has[Settings]](
      kept: Vector[Kept]): Unit ! R =
    menu[Int, R]("Continue a game", (kept.zipWithIndex.map((k, i) => k.label -> i) :+ ("Back" -> -1))*).flatMap {
      case -1 => pure(())
      case i => open[R](kept(i).params)
    }

  /** the board's size chosen, then `game` of it; nothing when Back */
  def sized[R[_]: Has[Ui]](game: Int => Unit ! R): Unit ! R =
    menu[Int, R]("The board", "9 × 9 — a quick game" -> 9, "13 × 13" -> 13, "19 × 19 — the full board" -> 19,
      "Back" -> 0).flatMap(s => if s == 0 then pure(()) else game(s))

  /** a new game at this screen, made on the server with both keys here (the socket's seat: the person's, or
   * Black's for two), its link the page's address */
  def fresh[R[_]: Has[Ui]: Has[Model]: Has[Remote]: Has[Place]: Has[Clipboard]: Has[Settings]](mode: Mode,
      size: Int): Unit ! R =
    val own = mode match { case Mode.Against(c) => c; case Mode.Two => Black }
    perform[Remote](Remote.Create(own, size)).flatMap { i =>
      val keys = Keys(i.game, Map(Black -> i.black, White -> i.white))
      perform[Remote](Remote.Join(i.game, Some(keys.of(own)), Some(i)))
        .andThen(perform[Place](Place.Write(keys.link(mode))))
        .andThen(go[R](Table(mode, Some(keys)), Rules.startOf(size), Nil))
    }

  /** a kept game opened again: the moves the server has (a screen waits for them, Menu its way out), then on —
   * as two people, or against the model (the person the socket's seat) */
  def resume[R[_]: Has[Ui]: Has[Model]: Has[Remote]: Has[Place]: Has[Clipboard]: Has[Settings]](g: String, k: String,
      other: String, model: Boolean): Unit ! R =
    perform[Remote](Remote.Join(g, Some(k), None)).andThen(perform[Place](Place.Write(
      Map("g" -> g, "k" -> k, (if model then "m" else "o") -> other)))).andThen(
    perform[Remote](Remote.Updates(0))).flatMap { updates =>
      show(Screen[Option[Seen]]("Go")(Part.info("Opening the game…"))(Button("Menu")(None))
        .until(updates.map(Some(_)))).flatMap {
        case None => leave[R]
        case Some(s) if s.view.seat == Room.Watch => perform[Remote](Remote.Leave()).andThen(
          Online.join[R](g, None, None))
        case Some(s) =>
          val v = s.view; val seat = v.seat
          val mode = if model then Mode.Against(seat) else Mode.Two
          val table = Table(mode, Some(Keys(g, Map(seat -> k, Rules.other(seat) -> other))))
          val pos = v.game.position
          if v.game.over then end[R](table, pos, v.resigned) else go[R](table, pos, Nil)
      }
    }

  /** the game where it stands: the model's turn, or a person's */
  private def go[R[_]: Has[Ui]: Has[Model]: Has[Remote]: Has[Place]: Has[Clipboard]: Has[Settings]](t: Table,
      pos: Position, said: List[Part]): Unit ! R = t.mode match
    case Mode.Against(c) if pos.toPlay != c && !pos.over => modelMoves[R](t, pos)
    case _ => turn[R](t, pos, said)

  /** the game left: its socket closed, the address cleared */
  private def leave[R[_]: Has[Remote]: Has[Place]]: Unit ! R =
    perform[Remote](Remote.Leave()).andThen(perform[Place](Place.Write(Map.empty)))

  /** `seat`'s action sent to the server (when it keeps the game), and this browser's list told */
  private def kept[R[_]: Has[Remote]: Has[Settings]](t: Table, seat: Int, a: Action, after: Position,
      resigned: Boolean): Unit ! R = t.keys match
    case None => pure(())
    case Some(keys) =>
      val own = t.mode match { case Mode.Against(c) => c; case Mode.Two => Black }
      perform[Remote](Remote.Send(a, Some(keys.of(seat)))).andThen(Kept.note[R](Kept(keys.link(t.mode),
        after.size, after.moves, own), over = resigned || after.over))

  val rules: String = "Black and White take turns to put a stone on an empty point; Black begins. A group of stones " +
    "with no empty point next to it is captured and taken off. A stone may not be put where its group would have no " +
    "liberty (unless it captures), and no move may bring back a position seen before (ko). Instead of a stone you " +
    "may pass; two passes in a row end the game. Then each side counts its stones and the empty points that reach " +
    "only its stones; White adds komi — 6.5 on 9 × 9, 7.5 on 13 × 13 and 19 × 19. Capture dead stones before you " +
    "pass: the board is counted as it stands."

  enum Act:
    case Stone(p: Int)
    case Pass, Resign, Menu, Copy

  private def status(pos: Position): String =
    s"${Board.word(pos.toPlay)} to play · move ${pos.moves + 1} · captured by Black ${pos.captured(Black)}, " +
      s"by White ${pos.captured(White)}"

  private def heading(mode: Mode, pos: Position): Part = mode match
    case Mode.Two => Part.text(status(pos))
    case Mode.Against(c) => Part.text(s"You play ${Board.word(c)} · " + status(pos))

  /** the game's own link, to go on with it on another device: a QR code for a phone's camera, and the link */
  private def elsewhere[R[_]: Has[Place]](t: Table): List[Part] ! R = t.keys match
    case None => pure(Nil)
    case Some(keys) => perform[Place](Place.Base()).map { base =>
      val link = s"$base#${Place.print(keys.link(t.mode))}"
      List(Part.section("Continue on another device")(Part.qr(link, "this game's link"), Part.text(link)))
    }

  /** a person's turn: a button at each legal point, Pass, Resign, Menu — and the game's link to copy */
  def turn[R[_]: Has[Ui]: Has[Model]: Has[Remote]: Has[Place]: Has[Clipboard]: Has[Settings]](t: Table,
      pos: Position, said: List[Part]): Unit ! R =
    if pos.over then end[R](t, pos, None)
    else elsewhere[R](t).flatMap { link =>
      val legal = Rules.legal(pos)
      val points = legal.map(p => Button(pos.name(p))(Act.Stone(p)).at(s"p$p"))
      show(Screen[Act]("Go")((Board(pos, legal.toSet) +: heading(t.mode, pos) +: (said ++ link))*)(
        (points ++ Seq(Button("Pass")(Act.Pass), Button("Resign", Style.Danger)(Act.Resign),
          Button("Menu")(Act.Menu)) ++ (if link.isEmpty then Nil else Seq(Button("Copy this game's link")(
          Act.Copy))))*)).flatMap {
        case Act.Menu => leave[R]
        case Act.Copy => elsewhereText[R](t).flatMap(l => copy[R](l)).flatMap(done => defer(turn[R](t, pos,
          List(if done then Part.info("This game's link is copied: open it on the other device.")
            else Part.warning("This browser does not let the page copy: copy the link from the text.")))))
        case Act.Resign => kept[R](t, pos.toPlay, Action.Resign, pos, resigned = true)
          .andThen(end[R](t, pos, Some(pos.toPlay)))
        case Act.Pass =>
          val next = Rules.pass(pos)
          kept[R](t, pos.toPlay, Action.Pass, next, resigned = false).andThen(after[R](t, next))
        case Act.Stone(p) =>
          val next = Rules.play(pos, p)
          kept[R](t, pos.toPlay, Action.Move(p), next, resigned = false).andThen(after[R](t, next))
      }
    }

  private def elsewhereText[R[_]: Has[Place]](t: Table): String ! R =
    perform[Place](Place.Base()).map(base => s"$base#${t.keys.fold("")(k => Place.print(k.link(t.mode)))}")

  /** after a person's move: the model's turn when it is the model's, else the next person's */
  def after[R[_]: Has[Ui]: Has[Model]: Has[Remote]: Has[Place]: Has[Clipboard]: Has[Settings]](t: Table,
      pos: Position): Unit ! R = defer(go[R](t, pos, Nil))

  /** the model's move: a working screen while it thinks — the board, "The model is thinking…" — with Menu as its way
   * out: pressed, the thinking is cancelled (in a page, its `Async` wait) and the game left */
  def modelMoves[R[_]: Has[Ui]: Has[Model]: Has[Remote]: Has[Place]: Has[Clipboard]: Has[Settings]](t: Table,
      pos: Position): Unit ! R =
    interruptible[Unit, Thought, R](Button("Menu")(()))(
      working[R]("Go")(Board(pos, Set.empty), heading(t.mode, pos), Part.info("The model is thinking…"))
        .andThen(perform[Model](Model.Think(pos)))).flatMap {
      case Left(_) => leave[R]
      case Right(th) => played[R](t, pos, th)
    }

  /** the model's thought played: its point if legal (a search's always is), else a pass */
  private def played[R[_]: Has[Ui]: Has[Model]: Has[Remote]: Has[Place]: Has[Clipboard]: Has[Settings]](t: Table,
      pos: Position, th: Thought): Unit ! R =
    val legal = th.move != pos.passIndex && Rules.tryPlay(pos, th.move).isRight
    val next = if legal then Rules.play(pos, th.move) else Rules.pass(pos)
    val note = Part.details("The model played" -> (if legal then pos.name(th.move) else "pass"),
      "The model thinks" -> th.note)
    // how the model works, at the bottom: which model, on what, and the last move's numbers (the handler's words)
    val engine = if th.about.isEmpty then Nil else List(Part.section("The engine")(Part.details(th.about*)))
    kept[R](t, pos.toPlay, if legal then Action.Move(th.move) else Action.Pass, next, resigned = false).andThen(
      if next.over then end[R](t, next, None) else defer(turn[R](t, next, note :: engine)))

  /** the end: by two passes — the score by area, each point's owner drawn — or by a resignation; the game left */
  def end[R[_]: Has[Ui]: Has[Remote]: Has[Place]](t: Table, pos: Position, resigned: Option[Int]): Unit ! R =
    val (black, white) = Rules.score(pos.board)
    val result = resigned.fold(Rules.result(pos.board))(c => s"${Board.word(c)} resigns: ${Board.word(
      Rules.other(c))} wins")
    val parts = Seq(Board(pos, Set.empty, areas = resigned.isEmpty), Part.info(result)) ++
      (if resigned.isEmpty then Seq(Part.details("Black" -> s"$black (stones and the points they surround)",
        "White" -> f"$white%.1f (stones, points, and komi ${pos.geo.komi}%.1f)")) else Nil)
    show(Screen[Unit]("Game over")(parts*)(Button("Back to the menu", Style.Primary)(()))).andThen(
      if t.keys.isDefined then leave[R] else pure(()))
