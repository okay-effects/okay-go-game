package okay.go

import okay.business.scala3.*
import okay.go.Rules.{Black, Empty, Position, White}

/** THE BOARD, of any size: a picture (the wood, the lines, the star points, the stones, the last move, at the end each
 * point's owner) and over it a grid of points, a button at each point the side to play may take — the others have
 * none. Every shape is marked (`go-black`, `go-line`, ...); the grid is marked with the board's size
 * (`go-points-<colour>-<size>`); how a mark looks is the skin's */
object Board:
  val cell = 40; val margin = 30
  def side(size: Int): Int = 2 * margin + (size - 1) * cell
  private def at(i: Int): Double = margin + i * cell

  def picture(pos: Position, areas: Boolean = false): Part =
    import Part.Shape.{Box, Label}
    val geo = pos.geo; val n = geo.size; val s = side(n); val len = (n - 1) * cell
    val lines = (0 until n).flatMap(i => List(Box(margin, at(i) - 0.75, len, 1.5, "go-line", ""),
      Box(at(i) - 0.75, margin, 1.5, len, "go-line", "")))
    val starPoints = geo.stars.toList.map(p => Box(at(p % n) - 3.5, at(p / n) - 3.5, 7, 7, "go-star", ""))
    val labels = (0 until n).flatMap(i => List(Label(at(i), 17, geo.name(i).take(1), "middle"),
      Label(13, at(i) + 4.5, (n - i).toString, "middle")))
    val stones = geo.points.filter(pos.board(_) != Empty).map { p =>
      val c = pos.board(p)
      Box(at(p % n) - 18, at(p / n) - 18, 36, 36, s"go-${Stones.word(c)}", s"${geo.name(p)} ${Stones.word(c)}")
    }
    val last = pos.last.toList.map(p => Box(at(p % n) - 5, at(p / n) - 5, 10, 10,
      s"go-last-on-${Stones.word(pos.board(p))}", "the last move"))
    val owners = if !areas then Nil else
      val a = Rules.area(pos.board)
      geo.points.filter(p => pos.board(p) == Empty && a(p) != Empty).map(p =>
        Box(at(p % n) - 6, at(p / n) - 6, 12, 12, s"go-area-${Stones.word(a(p))}", ""))
    val text = geo.points.filter(pos.board(_) != Empty).map(p => s"${geo.name(p)} ${Stones.word(pos.board(p))}")
      .mkString(", ")
    Part.picture(s, s, if text.isEmpty then s"an empty board, $n × $n" else text)(
      (Box(0, 0, s, s, "go-wood", "") +: (lines ++ starPoints ++ labels ++ stones ++ last ++ owners))*)

  /** the board with its points: a slot at each point in `open` (named "p<point>"), the others bare */
  def apply(pos: Position, open: Set[Int], areas: Boolean = false): Part =
    val turn = if pos.toPlay == Black then "black" else "white"
    Part.block("go")(picture(pos, areas), Part.block(s"go-points-$turn-${pos.size}")(pos.geo.points.map(p =>
      if open(p) then Part.block("pt")(Part.slot(s"p$p")) else Part.block("pt")())*))

  def word(c: Int): String = if c == White then "White" else "Black"

/** colours by name, for the screens */
object Stones:
  def word(c: Int): String = if c == Black then "black" else if c == White then "white" else "empty"
