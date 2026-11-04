package okay.go.net

import okay.go.Rules
import okay.go.Rules.{Position, pass, play, startOf}
import java.io.{BufferedReader, InputStreamReader, PrintWriter}
import java.nio.file.{Files, Path}

/**
 * KATAGO'S OWN ANSWERS, the fixture the reference is checked against: positions — the empty boards and games played
 * at random by our rules (passes, captures, ladders, territory among them) — given to Homebrew's `katago` (FP32, the
 * rules of this game) and its `kata-raw-nn 0` kept in net/jvm/src/test/resources/truth.txt.
 * `sbt "netJVM/Test/runMain okay.go.net.Truth"` (needs `katago` on the PATH; run once, the file committed).
 */
object Truth:
  val model = "nets/g170e-b10c128-s1141046784-d204142634.txt.gz"
  val file = Path.of("net/jvm/src/test/resources/truth.txt")

  /** a game at random: legal moves, no own eye filled, a pass now and then */
  def game(size: Int, length: Int, seed: Int): Position =
    val r = new scala.util.Random(seed)
    var pos = startOf(size)
    while pos.moves < length && !pos.over do
      val moves = Rules.legal(pos).filterNot(p => pos.geo.near(p).forall(pos.board(_) == pos.toPlay))
      pos = if moves.isEmpty || r.nextInt(30) == 0 then pass(pos) else play(pos, moves(r.nextInt(moves.size)))
    pos
  def positions: Vector[Position] =
    Vector(startOf(9), startOf(13), startOf(19)) ++
      (for size <- Seq(9, 13, 19); (length, seed) <- Seq(3 -> 1, 12 -> 2, 30 -> 3, size * size / 2 -> 4)
        yield game(size, length, seed + size))

  def main(args: Array[String]): Unit =
    // Homebrew's example config, its rules written out one by one (no suicide, positional superko, area), FP32
    val cfg = Path.of("nets/katago-truth.cfg")
    val proc = ProcessBuilder("katago", "gtp", "-model", model, "-config", cfg.toString).redirectErrorStream(false)
      .start()
    val in = BufferedReader(InputStreamReader(proc.getInputStream))
    val out = PrintWriter(proc.getOutputStream, true)
    def ask(cmd: String): Vector[String] =
      out.println(cmd)
      var lines = Vector.empty[String]; var line = in.readLine()
      while line != null && !(line.isEmpty && lines.nonEmpty) do
        if line.nonEmpty then lines :+= line
        line = in.readLine()
      require(lines.headOption.exists(_.startsWith("=")), s"$cmd: ${lines.mkString(" | ")}")
      lines
    val w = PrintWriter(Files.newBufferedWriter(file))
    for pos <- positions do
      val size = pos.size
      ask(s"boardsize $size"); ask("clear_board"); ask(s"komi ${pos.geo.komi}")
      var colour = "B"
      for m <- pos.record do
        ask(s"play $colour ${if m == pos.passIndex then "pass" else pos.geo.name(m)}")
        colour = if colour == "B" then "W" else "B"
      val raw = ask("kata-raw-nn 0")
      w.println(s"position $size ${pos.record.map(m => if m == pos.passIndex then "pass" else pos.geo.name(m))
        .mkString(" ")}")
      raw.drop(1).foreach(w.println)
      w.println("end")
      println(s"$size x $size after ${pos.record.size} moves")
    w.close(); out.println("quit"); proc.waitFor()
