package okay.go

import okay.business.scala3.*
import okay.go.Rules.{Black, Empty, N, Position, White}

/** THE BOARD: a picture (the wood, the lines, the star points, the stones, the last move, at the end each point's
 * owner) and over it a grid of 81 points, a button at each point the side to play may take — the others have none.
 * Every shape is marked (`go-black`, `go-line`, ...); how a mark looks is the skin's */
object Board:
  val cell = 40; val margin = 30
  val size: Int = 2 * margin + (N - 1) * cell
  private def at(i: Int): Double = margin + i * cell
  private val stars = Set((2, 2), (2, 6), (6, 2), (6, 6), (4, 4))
  private val letters = "ABCDEFGHJ"

  def picture(pos: Position, areas: Boolean = false): Part =
    import Part.Shape.{Box, Label}
    val lines = (0 until N).flatMap(i => List(Box(margin, at(i) - 0.75, (N - 1) * cell, 1.5, "go-line", ""),
      Box(at(i) - 0.75, margin, 1.5, (N - 1) * cell, "go-line", "")))
    val starPoints = stars.toList.map((r, c) => Box(at(c) - 3.5, at(r) - 3.5, 7, 7, "go-star", ""))
    val labels = (0 until N).flatMap(i => List(Label(at(i), 17, letters(i).toString, "middle"),
      Label(13, at(i) + 4.5, (N - i).toString, "middle")))
    val stones = Rules.points.filter(pos.board(_) != Empty).map { p =>
      val c = pos.board(p)
      Box(at(p % N) - 18, at(p / N) - 18, 36, 36, s"go-${Stones.word(c)}", s"${Rules.name(p)} ${Stones.word(c)}")
    }
    val last = pos.last.toList.map(p => Box(at(p % N) - 5, at(p / N) - 5, 10, 10,
      s"go-last-on-${Stones.word(pos.board(p))}", "the last move"))
    val owners = if !areas then Nil else
      val a = Rules.area(pos.board)
      Rules.points.filter(p => pos.board(p) == Empty && a(p) != Empty).map(p =>
        Box(at(p % N) - 6, at(p / N) - 6, 12, 12, s"go-area-${Stones.word(a(p))}", ""))
    val text = Rules.points.filter(pos.board(_) != Empty).map(p => s"${Rules.name(p)} ${Stones.word(pos.board(p))}")
      .mkString(", ")
    Part.picture(size, size, if text.isEmpty then "an empty board" else text)(
      (Box(0, 0, size, size, "go-wood", "") +: (lines ++ starPoints ++ labels ++ stones ++ last ++ owners))*)

  /** the board with its points: a slot at each point in `open` (named "p<point>"), the others bare */
  def apply(pos: Position, open: Set[Int], areas: Boolean = false): Part =
    val turn = if pos.toPlay == Black then "black" else "white"
    Part.block("go")(picture(pos, areas), Part.block(s"go-points-$turn")(Rules.points.map(p =>
      if open(p) then Part.block("pt")(Part.slot(s"p$p")) else Part.block("pt")())*))

  def word(c: Int): String = if c == White then "White" else "Black"
