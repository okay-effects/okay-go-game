package okay.go

/** THE RULES OF GO on 9 x 9, pure: stones, groups and their liberties, captures, no suicide, no position repeated
 * (positional superko), passes, and the score by area (Tromp-Taylor: stones plus the empty points that reach only
 * one colour), komi 6.5 to White */
object Rules:
  val N = 9
  val points: Range = 0 until N * N
  type Board = Vector[Int]
  val Empty = 0; val Black = 1; val White = 2
  def other(c: Int): Int = 3 - c
  def colour(c: Int): String = if c == Black then "Black" else "White"
  val komi = 6.5
  private val letters = "ABCDEFGHJ"
  /** a point's name: its column's letter (no I), its row counted from the bottom — "E5" the centre */
  def name(p: Int): String = s"${letters(p % N)}${N - p / N}"
  val neighbours: Vector[Vector[Int]] = Vector.tabulate(N * N) { p =>
    val (r, c) = (p / N, p % N)
    Vector((r - 1, c), (r + 1, c), (r, c - 1), (r, c + 1)).collect {
      case (a, b) if a >= 0 && a < N && b >= 0 && b < N => a * N + b
    }
  }
  val empty: Board = Vector.fill(N * N)(Empty)

  /** the group of the stone at `p`: its stones and its liberties */
  def group(b: Board, p: Int): (Set[Int], Set[Int]) =
    val c = b(p)
    var stones = Set(p); var libs = Set.empty[Int]; var todo = List(p)
    while todo.nonEmpty do
      val q = todo.head; todo = todo.tail
      for n <- neighbours(q) do
        if b(n) == Empty then libs += n
        else if b(n) == c && !stones(n) then { stones += n; todo = n :: todo }
    (stones, libs)

  enum Why:
    case Occupied, Suicide, Repeats
    def text: String = this match
      case Occupied => "the point is taken"
      case Suicide => "the stone would have no liberty"
      case Repeats => "it repeats a position (ko)"

  /** A POSITION: the board, who plays, every board seen (no repeat), passes in a row, stones captured by each colour,
   * the last move */
  final case class Position(board: Board, toPlay: Int, seen: Set[Board], passes: Int, captured: Map[Int, Int],
      last: Option[Int], moves: Int):
    def over: Boolean = passes >= 2

  val start: Position = Position(empty, Black, Set(empty), 0, Map(Black -> 0, White -> 0), None, 0)

  /** a stone of `pos.toPlay` at `p`: the board after it and the stones it captured, or why it may not be played */
  def tryPlay(pos: Position, p: Int): Either[Why, (Board, Int)] =
    val b = pos.board; val c = pos.toPlay
    if b(p) != Empty then Left(Why.Occupied)
    else
      var nb = b.updated(p, c); var taken = 0
      for n <- neighbours(p) if nb(n) == other(c) do
        val (stones, libs) = group(nb, n)
        if libs.isEmpty then { stones.foreach(s => nb = nb.updated(s, Empty)); taken += stones.size }
      if group(nb, p)._2.isEmpty then Left(Why.Suicide)
      else if pos.seen(nb) then Left(Why.Repeats)
      else Right((nb, taken))

  def legal(pos: Position): Vector[Int] = points.filter(tryPlay(pos, _).isRight).toVector

  def play(pos: Position, p: Int): Position = tryPlay(pos, p) match
    case Right((nb, taken)) => Position(nb, other(pos.toPlay), pos.seen + nb, 0,
        pos.captured.updated(pos.toPlay, pos.captured(pos.toPlay) + taken), Some(p), pos.moves + 1)
    case Left(why) => throw IllegalArgumentException(s"${name(p)}: ${why.text}")

  def pass(pos: Position): Position = pos.copy(toPlay = other(pos.toPlay), passes = pos.passes + 1, last = None,
    moves = pos.moves + 1)

  /** who each point belongs to by area: a stone its colour; an empty region the colour it alone reaches, else none */
  def area(b: Board): Vector[Int] = regions(b).map(_._1)

  /** each point's owner by area, and for an empty point the size of its empty region (0 for a stone) */
  def regions(b: Board): Vector[(Int, Int)] =
    val owner = Array.tabulate(N * N)(b(_))
    val sizes = Array.fill(N * N)(0)
    val done = Array.fill(N * N)(false)
    for p <- points if b(p) == Empty && !done(p) do
      var region = List(p); var todo = List(p); var touches = Set.empty[Int]; done(p) = true
      while todo.nonEmpty do
        val q = todo.head; todo = todo.tail
        for n <- neighbours(q) do
          if b(n) == Empty then { if !done(n) then { done(n) = true; region = n :: region; todo = n :: todo } }
          else touches += b(n)
      val own = if touches.size == 1 then touches.head else Empty
      region.foreach { q => owner(q) = own; sizes(q) = region.size }
    owner.toVector.zip(sizes.toVector)

  /** the score: Black's area, and White's area plus komi */
  def score(b: Board): (Int, Double) =
    val a = area(b); (a.count(_ == Black), a.count(_ == White) + komi)

  def result(b: Board): String =
    val (black, white) = score(b)
    if black > white then f"Black wins by ${black - white}%.1f" else f"White wins by ${white - black}%.1f"
