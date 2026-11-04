package okay.go.net

import okay.go.{Geometry, Rules}
import okay.go.Rules.{Position, White}
import java.nio.file.{Files, Path}
import java.util.zip.GZIPInputStream

/** THE REFERENCE AGAINST KATAGO ITSELF: for every position of the fixture (truth.txt, made by `Truth`), our inputs
 * and forward pass, postprocessed as KataGo does (the policy's softmax over the legal moves and the pass, the value's
 * softmax with no result taken out, white's view, ownership's tanh), equal to `kata-raw-nn 0` within float rounding */
class NetSuite extends munit.FunSuite:
  override val munitTimeout = scala.concurrent.duration.Duration(10, "min")
  lazy val model: Model =
    Model.parse(String(GZIPInputStream(Files.newInputStream(Path.of(Truth.model))).readAllBytes(), "UTF-8"))

  final case class Answer(size: Int, moves: Vector[String], whiteWin: Double, policy: Array[Double], pass: Double,
      own: Array[Double])
  def answers: Vector[Answer] =
    val lines = Files.readAllLines(Path.of("net/jvm/src/test/resources/truth.txt")).toArray(Array.empty[String])
    var i = 0; var out = Vector.empty[Answer]
    while i < lines.length do
      val head = lines(i).split(' ').toVector; i += 1
      val size = head(1).toInt; val moves = head.drop(2)
      var kv = Map.empty[String, String]; var policy = Array.empty[Double]; var own = Array.empty[Double]
      while lines(i) != "end" do
        lines(i) match
          case "policy" =>
            policy = (1 to size).flatMap(k => lines(i + k).trim.split(" +").map(s => if s == "NAN" then -1.0
              else s.toDouble)).toArray; i += size
          case "whiteOwnership" =>
            own = (1 to size).flatMap(k => lines(i + k).trim.split(" +").map(_.toDouble)).toArray; i += size
          case l => val ws = l.trim.split(" +"); if ws.length == 2 then kv += ws(0) -> ws(1)
        i += 1
      i += 1
      out :+= Answer(size, moves, kv("whiteWin").toDouble, policy, kv("policyPass").toDouble, own)
    out

  def replay(size: Int, moves: Vector[String]): Position =
    val geo = Geometry(size)
    moves.foldLeft(Rules.startOf(size))((p, m) =>
      if m == "pass" then Rules.pass(p) else Rules.play(p, geo.point(m).get))

  test("the model read: its shape and its parameters") {
    assertEquals((model.version, model.spatial, model.global, model.blocks.size), (8, 22, 19, 10))
    assertEquals(model.blocks.map { case _: Ordinary => "O"; case _: Pooling => "G" }.mkString, "OOOOGOOGOO")
    println(s"PARAMETERS ${model.parameters}")
  }

  test("every position of the fixture: policy, value, ownership as KataGo's, within rounding") {
    val forward = Forward(model)
    var worstP = 0.0; var worstV = 0.0; var worstO = 0.0
    for a <- answers do
      val pos = replay(a.size, a.moves)
      val (spatial, global) = Inputs.of(pos)
      val t0 = System.nanoTime()
      val raw = forward.run(a.size, spatial, global)
      val ms = (System.nanoTime() - t0) / 1000000
      val legal = Rules.legal(pos).toSet
      val logits = (0 until a.size * a.size)
        .map(p => if legal(p) then raw.policy(p).toDouble else Double.NegativeInfinity) :+ raw.pass.toDouble
      val mx = logits.max; val e = logits.map(l => math.exp(l - mx)); val z = e.sum
      val probs = e.map(_ / z)
      val (w, l) = (raw.value(0).toDouble, raw.value(1).toDouble)
      val pWin = math.exp(w) / (math.exp(w) + math.exp(l))
      val whiteWin = if pos.toPlay == White then pWin else 1 - pWin
      val sign = if pos.toPlay == White then 1.0 else -1.0
      val dP = (0 until a.size * a.size).filter(legal).map(p => math.abs(probs(p) - a.policy(p)))
        .maxOption.getOrElse(0.0).max(math.abs(probs.last - a.pass))
      val dV = math.abs(whiteWin - a.whiteWin)
      val dO = (0 until a.size * a.size).map(p => math.abs(sign * math.tanh(raw.ownership(p)) - a.own(p))).max
      val illegalAgree = (0 until a.size * a.size).forall(p => legal(p) == (a.policy(p) >= 0))
      println(f"POSITION ${a.size}%2d after ${a.moves.size}%3d: policy ${dP}%.2e value ${dV}%.2e own ${dO}%.2e " +
        f"legal-agree $illegalAgree ($ms ms)")
      assert(illegalAgree, s"${a.size} after ${a.moves.size}: the legal moves differ")
      worstP = worstP.max(dP); worstV = worstV.max(dV); worstO = worstO.max(dO)
    println(f"WORST policy $worstP%.2e value $worstV%.2e ownership $worstO%.2e")
    assert(worstP < 1e-4 && worstV < 1e-4 && worstO < 1e-4)
  }
