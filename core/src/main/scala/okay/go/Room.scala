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

  final case class Game(size: Int, black: String, white: String, moves: Vector[Int], resigned: Option[Int]):
    lazy val position: Rules.Position =
      moves.foldLeft(Rules.startOf(size))((p, m) => if m < 0 then Rules.pass(p) else Rules.play(p, m))
    def over: Boolean = resigned.isDefined || position.over
    def seat(key: String): Int = if key == black then Black else if key == white then White else Watch

  enum Action:
    case Move(point: Int)
    case Pass, Resign

  /** an action of `seat`: the game after it, or why not — a move or a pass made `at` a number of moves refused when
   * the game has moved on (another device's came first) */
  def act(g: Game, seat: Int, a: Action, at: Option[Int] = None): Either[String, Game] =
    if g.over then Left("the game is over")
    else if a != Action.Resign && at.exists(_ != g.moves.size) then Left("another move came first")
    else if seat == Watch then Left("a watcher cannot play")
    else a match
      case Action.Resign => Right(g.copy(resigned = Some(seat)))
      case _ if seat != g.position.toPlay => Left(s"it is ${Rules.colour(g.position.toPlay)}'s turn")
      case Action.Pass => Right(g.copy(moves = g.moves :+ -1))
      case Action.Move(p) => Rules.tryPlay(g.position, p).fold(why => Left(why.text), _ => Right(g.copy(moves =
        g.moves :+ p)))

  /** an action as said on the socket — with the key of the seat it is for, when the socket's own is not that seat's
   * (a page that holds both: the model's seat, or two people at one screen) */
  def writeAction(a: Action, key: Option[String] = None, at: Option[Int] = None): String =
    Json.print(Json.Obj((a match
      case Action.Move(p) => Vector("move" -> Json.Num(BigDecimal(p)))
      case Action.Pass => Vector("pass" -> Json.Bool(true))
      case Action.Resign => Vector("resign" -> Json.Bool(true))) ++ key.map("key" -> Json.Str(_)) ++
      at.map(n => "at" -> Json.Num(BigDecimal(n)))))
  def readAction(text: String): Option[Action] = readKeyed(text).map(_._1)
  /** an action, the key it is for and the number of moves it was made at, as said */
  def readKeyed(text: String): Option[(Action, Option[String], Option[Int])] =
    Descent.parse(text).toOption.collect { case o: Json.Obj => o }.flatMap { o =>
      o.get("move").collect { case Json.Num(n) if n.isValidInt && n >= 0 => Action.Move(n.toInt) }
        .orElse(o.get("pass").collect { case Json.Bool(true) => Action.Pass })
        .orElse(o.get("resign").collect { case Json.Bool(true) => Action.Resign })
        .map(a => (a, o.get("key").collect { case Json.Str(k) if wellFormed(k) => k },
          o.get("at").collect { case Json.Num(n) if n.isValidInt => n.toInt }))
    }

  /** the game as stored */
  def write(g: Game): Json = Json.obj("size" -> Json.Num(BigDecimal(g.size)), "black" -> Json.Str(g.black),
    "white" -> Json.Str(g.white),
    "moves" -> Json.Arr(g.moves.map(m => Json.Num(BigDecimal(m)))),
    "resigned" -> g.resigned.fold[Json](Json.Null)(c => Json.Num(BigDecimal(c))))
  def read(j: Json): Option[Game] = j match
    case o: Json.Obj =>
      for
        case Json.Str(b) <- o.get("black")
        case Json.Str(w) <- o.get("white")
        case Json.Arr(ms) <- o.get("moves")
      yield Game(sizeOf(o), b, w, ms.collect { case Json.Num(n) => n.toInt },
        o.get("resigned").collect { case Json.Num(n) => n.toInt })
    case _ => None

  /** WHAT A SOCKET IS TOLD: the moves and the resignation, its own seat, who of the players is here, how many watch,
   * and the reason its last action was refused */
  final case class View(size: Int, moves: Vector[Int], resigned: Option[Int], seat: Int, black: Boolean,
      white: Boolean, watching: Int, error: Option[String] = None):
    lazy val game: Game = Game(size, "", "", moves, resigned)

  def writeView(v: View): String = Json.print(Json.Obj(Vector("size" -> Json.Num(BigDecimal(v.size)),
    "moves" -> Json.Arr(v.moves.map(m => Json.Num(BigDecimal(m)))),
    "resigned" -> v.resigned.fold[Json](Json.Null)(c => Json.Num(BigDecimal(c))),
    "seat" -> Json.Str(seatName(v.seat)), "black" -> Json.Bool(v.black), "white" -> Json.Bool(v.white),
    "watching" -> Json.Num(BigDecimal(v.watching))) ++ v.error.map(e => "error" -> Json.Str(e))))
  def readView(text: String): Option[View] =
    Descent.parse(text).toOption.collect { case o: Json.Obj => o }.flatMap { o =>
    for
      case Json.Arr(ms) <- o.get("moves")
      case Json.Str(seat) <- o.get("seat")
    yield View(sizeOf(o), ms.collect { case Json.Num(n) => n.toInt },
      o.get("resigned").collect { case Json.Num(n) => n.toInt },
      seatOf(seat), o.get("black").contains(Json.Bool(true)), o.get("white").contains(Json.Bool(true)),
      o.get("watching").collect { case Json.Num(n) => n.toInt }.getOrElse(0),
      o.get("error").collect { case Json.Str(e) => e })
  }

  /** a board's size as said, one of the sizes; 9 when not said (a game kept before sizes) */
  def sizeOf(o: Json.Obj): Int = o.get("size").collect { case Json.Num(n) if n.isValidInt => n.toInt }
    .filter(Geometry.sizes.contains).getOrElse(9)

  /** an id or a key: 32 lowercase hex digits */
  def wellFormed(s: String): Boolean = s.length == 32 && s.forall(c => c.isDigit || (c >= 'a' && c <= 'f'))
