package okay.go

/** THE RULES OF GO on any board (9, 13, 19), pure: stones, groups and their liberties, captures, no suicide, no
 * position repeated (positional superko), passes, and the score by area (Tromp-Taylor: stones plus the empty points
 * that reach only one colour), the komi the board's (`Geometry`) */
object Rules:
  type Board = Vector[Int]
  val Empty = 0; val Black = 1; val White = 2
  def other(c: Int): Int = 3 - c
  def colour(c: Int): String = if c == Black then "Black" else "White"
  def geo(b: Board): Geometry = Geometry.ofCount(b.length)
  def empty(size: Int): Board = Vector.fill(size * size)(Empty)

  /** the group of the stone at `p`: its stones and its liberties */
  def group(b: Board, p: Int): (Set[Int], Set[Int]) =
    val near = geo(b).near; val c = b(p)
    var stones = Set(p); var libs = Set.empty[Int]; var todo = List(p)
    while todo.nonEmpty do
      val q = todo.head; todo = todo.tail
      for n <- near(q) do
        if b(n) == Empty then libs += n
        else if b(n) == c && !stones(n) then { stones += n; todo = n :: todo }
    (stones, libs)

  enum Why:
    case Outside, Occupied, Suicide, Repeats
    def text: String = this match
      case Outside => "there is no such point"
      case Occupied => "the point is taken"
      case Suicide => "the stone would have no liberty"
      case Repeats => "it repeats a position (ko)"

  /** A POSITION: the board, who plays, every board seen (no repeat), passes in a row, stones captured by each colour,
   * the last move */
  final case class Position(board: Board, toPlay: Int, seen: Set[Board], passes: Int, captured: Map[Int, Int],
      last: Option[Int], moves: Int, record: Vector[Int] = Vector.empty, boards: List[Board] = Nil):
    def over: Boolean = passes >= 2
    def geo: Geometry = Rules.geo(board)
    def size: Int = geo.size
    /** the index that stands for a pass, after the points */
    def passIndex: Int = board.length
    def name(p: Int): String = if p == passIndex then "pass" else geo.name(p)
    /** the board `k` moves ago (0: now), as far as it is kept — the last two, for a network's inputs */
    def boardAgo(k: Int): Board =
      if k == 0 then board else boards.lift(k - 1).getOrElse(boards.lastOption.getOrElse(board))

  def startOf(size: Int): Position =
    val b = empty(size)
    Position(b, Black, Set(b), 0, Map(Black -> 0, White -> 0), None, 0)
  val start: Position = startOf(9)

  /** a stone of `pos.toPlay` at `p`: the board after it and the stones it captured, or why it may not be played */
  def tryPlay(pos: Position, p: Int): Either[Why, (Board, Int)] =
    val b = pos.board; val c = pos.toPlay; val near = pos.geo.near
    if p < 0 || p >= b.length then Left(Why.Outside)
    else if b(p) != Empty then Left(Why.Occupied)
    else
      var nb = b.updated(p, c); var taken = 0
      for n <- near(p) if nb(n) == other(c) do
        val (stones, libs) = group(nb, n)
        if libs.isEmpty then { stones.foreach(s => nb = nb.updated(s, Empty)); taken += stones.size }
      if group(nb, p)._2.isEmpty then Left(Why.Suicide)
      else if pos.seen(nb) then Left(Why.Repeats)
      else Right((nb, taken))

  def legal(pos: Position): Vector[Int] = pos.geo.points.filter(tryPlay(pos, _).isRight).toVector

  def play(pos: Position, p: Int): Position = tryPlay(pos, p) match
    case Right((nb, taken)) => Position(nb, other(pos.toPlay), pos.seen + nb, 0,
        pos.captured.updated(pos.toPlay, pos.captured(pos.toPlay) + taken), Some(p), pos.moves + 1, pos.record :+ p,
        (pos.board :: pos.boards).take(2))
    case Left(why) => throw IllegalArgumentException(s"${pos.name(p)}: ${why.text}")

  def pass(pos: Position): Position = pos.copy(toPlay = other(pos.toPlay), passes = pos.passes + 1, last = None,
    moves = pos.moves + 1, record = pos.record :+ pos.passIndex, boards = (pos.board :: pos.boards).take(2))

  /** who each point belongs to by area: a stone its colour; an empty region the colour it alone reaches, else none */
  def area(b: Board): Vector[Int] = regions(b).map(_._1)

  /** each point's owner by area, and for an empty point the size of its empty region (0 for a stone) */
  def regions(b: Board): Vector[(Int, Int)] =
    val near = geo(b).near
    val owner = Array.tabulate(b.length)(b(_))
    val sizes = Array.fill(b.length)(0)
    val done = Array.fill(b.length)(false)
    for p <- b.indices if b(p) == Empty && !done(p) do
      var region = List(p); var todo = List(p); var touches = Set.empty[Int]; done(p) = true
      while todo.nonEmpty do
        val q = todo.head; todo = todo.tail
        for n <- near(q) do
          if b(n) == Empty then { if !done(n) then { done(n) = true; region = n :: region; todo = n :: todo } }
          else touches += b(n)
      val own = if touches.size == 1 then touches.head else Empty
      region.foreach { q => owner(q) = own; sizes(q) = region.size }
    owner.toVector.zip(sizes.toVector)

  /** the score: Black's area, and White's area plus komi */
  def score(b: Board): (Int, Double) =
    val a = area(b); (a.count(_ == Black), a.count(_ == White) + geo(b).komi)

  def result(b: Board): String =
    val (black, white) = score(b)
    if black > white then f"Black wins by ${black - white}%.1f" else f"White wins by ${white - black}%.1f"
