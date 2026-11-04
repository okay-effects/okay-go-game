package okay.go.train

import mlx.*
import mlx.Mlx.*
import okay.go.{Geometry, Rules}
import okay.go.net.{Binary, Forward, Inputs, Model}
import java.nio.file.{Files, Path}

/** THE NETWORK IN MLX = `Forward` (the CPU reference, itself = KataGo): KataGo's b10c128 on positions of three
 * sizes, a batch at a time; a new student, written by `Binary` and read back, the same; its gradient flows */
class MlxNetSuite extends munit.FunSuite:
  override val munitTimeout = scala.concurrent.duration.Duration(10, "min")
  Mlx.init()

  def positions(size: Int, count: Int, seed: Int): Vector[Rules.Position] =
    val r = scala.util.Random(seed)
    Vector.tabulate(count) { k =>
      var p = Rules.startOf(size)
      for _ <- 0 until k * 3 do
        val legal = Rules.legal(p)
        if legal.nonEmpty then p = Rules.play(p, legal(r.nextInt(legal.size)))
      p
    }

  def same(model: Model, size: Int, tolerance: Float): Unit =
    val ps = positions(size, 6, size)
    val ins = ps.map(Inputs.of)
    val s = new Scope
    given Scope = s
    try
      val net = MlxNet(Weights.kinds(model))
      val w = MlxNet.arrays(Weights.of(model))
      val out = net(w, size, floats(ins.flatMap(_._1).toArray, ps.size, 22, size * size),
        floats(ins.flatMap(_._2).toArray, ps.size, 19))
      val (policy, pass, value, own) = (out.policy.toFloats, out.pass.toFloats, out.value.toFloats,
        out.ownership.toFloats)
      val area = size * size
      for k <- ps.indices do
        val raw = Forward(model).run(size, ins(k)._1, ins(k)._2)
        for p <- 0 until area do
          assertEqualsFloat(policy(k * area + p), raw.policy(p), tolerance, s"policy $k $p")
          assertEqualsFloat(own(k * area + p), raw.ownership(p), tolerance, s"ownership $k $p")
        assertEqualsFloat(pass(k), raw.pass, tolerance)
        for v <- 0 until 3 do assertEqualsFloat(value(k * 3 + v), raw.value(v), tolerance)
    finally s.close()

  lazy val teacher: Model = Binary.read(Files.readAllBytes(Path.of("nets/b10c128.bin")))

  test("KataGo's b10c128 in MLX = Forward, on 9 x 9, 13 x 13, 19 x 19"):
    for size <- Geometry.sizes do same(teacher, size, 2e-3f)

  val kinds = Vector(false, false, true, false, true, false)
  def student = Weights.student(kinds, 64, 16, 32, 64, scala.util.Random(7))

  test("a student, through Binary and back, the same tensors; in MLX = Forward"):
    val m = Weights.model("own", kinds, student)
    val back = Weights.of(Binary.read(Binary.write(m)))
    assertEquals(back.map(_.shape), student.map(_.shape))
    assert(back.zip(student).forall((a, b) => a.data.sameElements(b.data)))
    // its last convs zero: perturbed, so the comparison sees every layer
    val r = scala.util.Random(3)
    val noisy = student.map(t => Tensor(t.data.map(_ + (r.nextGaussian() * 0.05).toFloat), t.shape))
    same(Weights.model("own", kinds, noisy), 9, 1e-3f)
