package okay.go

import okay.business.scala3.*

/** A GAME THIS BROWSER PLAYS, kept on the games' server: its link's parameters (#g=…&k=… and &m=… for the model's
 * seat, or &o=… for the other person's at this screen), its board, how many moves it has, the seat of `k` */
final case class Kept(params: Map[String, String], size: Int, moves: Int, seat: Int):
  def game: String = params.getOrElse("g", "")
  def label: String =
    val kind =
      if params.contains("m") then s"Against the model · you play ${Board.word(seat)}"
      else if params.contains("o") then "Two players"
      else s"Online · you play ${Board.word(seat)}"
    s"$kind · $size × $size · move ${moves + 1}"

/**
 * THE GAMES OF THIS BROWSER, in okay-ui's `Settings` (a page's local storage) under "games": a line a game —
 * its link's parameters, the size, the moves, the seat — the latest first, at most 20; a finished game dropped.
 */
object Kept:
  private val name = "games"
  private def line(k: Kept): String = s"${Place.print(k.params)}\t${k.size}\t${k.moves}\t${k.seat}"
  private def parse(l: String): Option[Kept] = l.split('\t').toList match
    case List(ps, size, moves, seat) =>
      for s <- size.toIntOption; m <- moves.toIntOption; c <- seat.toIntOption
          if Place.parse(ps).get("g").exists(Room.wellFormed) yield Kept(Place.parse(ps), s, m, c)
    case _ => None

  def all[R[_]: Has[Settings]]: Vector[Kept] ! R =
    perform[Settings](Settings.Get(name)).map(_.toVector.flatMap(_.split('\n')).flatMap(parse))

  private def write[R[_]: Has[Settings]](ks: Vector[Kept]): Unit ! R =
    perform[Settings](Settings.Put(name, ks.take(20).map(line).mkString("\n")))

  /** the game as it is now, first; dropped when it is over */
  def note[R[_]: Has[Settings]](k: Kept, over: Boolean): Unit ! R =
    all[R].flatMap(ks => write[R]((if over then Vector.empty else Vector(k)) ++ ks.filterNot(_.game == k.game)))
