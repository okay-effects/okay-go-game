package okay.go

import okay.effects.json.{Descent, Json}
import okay.go.Rules.{Black, White}

/**
 * A GAME'S ROOM, shared by the page and the server: the two players' keys, the moves (a point, or -1 a pass), a
 * resignation; who may do what — a key is a seat (Black, White), anything else watches; and what is said on the
 * socket, both ways, as JSON. The position is the moves played again through the rules: a game's record, not a
 * program's continuation.
 */
object Room:
  val Watch = 0
  def seatName(s: Int): String = if s == Black then "black" else if s == White then "white" else "watch"
  def seatOf(name: String): Int = name match
    case "black" => Black
    case "white" => White
    case _ => Watch

  final case class Game(black: String, white: String, moves: Vector[Int], resigned: Option[Int]):
    lazy val position: Rules.Position =
      moves.foldLeft(Rules.start)((p, m) => if m < 0 then Rules.pass(p) else Rules.play(p, m))
    def over: Boolean = resigned.isDefined || position.over
    def seat(key: String): Int = if key == black then Black else if key == white then White else Watch

  enum Action:
    case Move(point: Int)
    case Pass, Resign

  /** an action of `seat`: the game after it, or why not */
  def act(g: Game, seat: Int, a: Action): Either[String, Game] =
    if g.over then Left("the game is over")
    else if seat == Watch then Left("a watcher cannot play")
    else a match
      case Action.Resign => Right(g.copy(resigned = Some(seat)))
      case _ if seat != g.position.toPlay => Left(s"it is ${Rules.colour(g.position.toPlay)}'s turn")
      case Action.Pass => Right(g.copy(moves = g.moves :+ -1))
      case Action.Move(p) => Rules.tryPlay(g.position, p).fold(why => Left(why.text), _ => Right(g.copy(moves =
        g.moves :+ p)))

  def writeAction(a: Action): String = Json.print(a match
    case Action.Move(p) => Json.obj("move" -> Json.Num(BigDecimal(p)))
    case Action.Pass => Json.obj("pass" -> Json.Bool(true))
    case Action.Resign => Json.obj("resign" -> Json.Bool(true)))
  def readAction(text: String): Option[Action] = Descent.parse(text).toOption.collect { case o: Json.Obj => o }
    .flatMap { o =>
      o.get("move").collect { case Json.Num(n) if n.isValidInt && Rules.points.contains(n.toInt) => Action.Move(n.toInt) }
        .orElse(o.get("pass").collect { case Json.Bool(true) => Action.Pass })
        .orElse(o.get("resign").collect { case Json.Bool(true) => Action.Resign })
    }

  /** the game as stored */
  def write(g: Game): Json = Json.obj("black" -> Json.Str(g.black), "white" -> Json.Str(g.white),
    "moves" -> Json.Arr(g.moves.map(m => Json.Num(BigDecimal(m)))),
    "resigned" -> g.resigned.fold[Json](Json.Null)(c => Json.Num(BigDecimal(c))))
  def read(j: Json): Option[Game] = j match
    case o: Json.Obj =>
      for
        case Json.Str(b) <- o.get("black")
        case Json.Str(w) <- o.get("white")
        case Json.Arr(ms) <- o.get("moves")
      yield Game(b, w, ms.collect { case Json.Num(n) => n.toInt },
        o.get("resigned").collect { case Json.Num(n) => n.toInt })
    case _ => None

  /** WHAT A SOCKET IS TOLD: the moves and the resignation, its own seat, who of the players is here, how many watch,
   * and the reason its last action was refused */
  final case class View(moves: Vector[Int], resigned: Option[Int], seat: Int, black: Boolean, white: Boolean,
      watching: Int, error: Option[String] = None):
    lazy val game: Game = Game("", "", moves, resigned)

  def writeView(v: View): String = Json.print(Json.Obj(Vector(
    "moves" -> Json.Arr(v.moves.map(m => Json.Num(BigDecimal(m)))),
    "resigned" -> v.resigned.fold[Json](Json.Null)(c => Json.Num(BigDecimal(c))),
    "seat" -> Json.Str(seatName(v.seat)), "black" -> Json.Bool(v.black), "white" -> Json.Bool(v.white),
    "watching" -> Json.Num(BigDecimal(v.watching))) ++ v.error.map(e => "error" -> Json.Str(e))))
  def readView(text: String): Option[View] = Descent.parse(text).toOption.collect { case o: Json.Obj => o }.flatMap { o =>
    for
      case Json.Arr(ms) <- o.get("moves")
      case Json.Str(seat) <- o.get("seat")
    yield View(ms.collect { case Json.Num(n) => n.toInt }, o.get("resigned").collect { case Json.Num(n) => n.toInt },
      seatOf(seat), o.get("black").contains(Json.Bool(true)), o.get("white").contains(Json.Bool(true)),
      o.get("watching").collect { case Json.Num(n) => n.toInt }.getOrElse(0),
      o.get("error").collect { case Json.Str(e) => e })
  }

  /** an id or a key: 32 lowercase hex digits */
  def wellFormed(s: String): Boolean = s.length == 32 && s.forall(c => c.isDigit || (c >= 'a' && c <= 'f'))
