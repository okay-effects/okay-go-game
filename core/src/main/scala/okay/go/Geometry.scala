package okay.go

/** A BOARD'S SHAPE: its size (9, 13 or 19), its points' names (a column's letter, no I; a row from the bottom), each
 * point's neighbours and diagonals, the star points, the komi (6.5 on 9 x 9, 7.5 on the larger boards) */
final class Geometry private (val size: Int):
  val count: Int = size * size
  val points: Range = 0 until count
  private val letters = "ABCDEFGHJKLMNOPQRST"
  def name(p: Int): String = s"${letters(p % size)}${size - p / size}"
  def point(name: String): Option[Int] = points.find(this.name(_) == name)
  private def around(p: Int, d: Seq[(Int, Int)]): Array[Int] =
    val (r, c) = (p / size, p % size)
    d.map((a, b) => (r + a, c + b)).collect { case (a, b) if a >= 0 && a < size && b >= 0 && b < size => a * size + b }
      .toArray
  val near: Array[Array[Int]] = Array.tabulate(count)(around(_, Seq((-1, 0), (1, 0), (0, -1), (0, 1))))
  val neighbours: Vector[Vector[Int]] = near.map(_.toVector).toVector
  val diagonal: Array[Array[Int]] = Array.tabulate(count)(around(_, Seq((-1, -1), (-1, 1), (1, -1), (1, 1))))
  /** the edge's distance of each point: 0 on the first line */
  val line: Array[Int] = Array.tabulate(count)(p =>
    Seq(p / size, p % size, size - 1 - p / size, size - 1 - p % size).min)
  val stars: Set[Int] =
    val e = if size >= 13 then 3 else 2
    val mid = size / 2
    val at = if size >= 19 then Seq(e, mid, size - 1 - e) else Seq(e, size - 1 - e)
    (for r <- at; c <- at yield r * size + c).toSet + (mid * size + mid)
  val komi: Double = if size <= 9 then 6.5 else 7.5

object Geometry:
  val sizes: Vector[Int] = Vector(9, 13, 19)
  private val made: Map[Int, Geometry] = sizes.map(s => s -> new Geometry(s)).toMap
  def apply(size: Int): Geometry = made.getOrElse(size, new Geometry(size))
  /** the shape of a board of `count` points */
  def ofCount(count: Int): Geometry = apply(math.round(math.sqrt(count.toDouble)).toInt)
