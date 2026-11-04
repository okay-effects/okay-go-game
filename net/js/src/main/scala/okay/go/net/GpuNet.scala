package okay.go.net

import scala.concurrent.{ExecutionContext, Future}
import scala.scalajs.js
import scala.scalajs.js.Dynamic.{global as g, literal}
import scala.scalajs.js.typedarray.*

/**
 * THE NETWORK ON WEBGPU: the weights in the device's buffers once; for a batch size and a board size a plan — every
 * dispatch with its bind group, built once — then each run writes the inputs, encodes the plan, and reads back the
 * policy, the pass and the value. The order of the work is `Forward`'s, kernel for step (`Kernels`).
 */
final class GpuNet private (device: js.Dynamic, model: Model)(using ExecutionContext):
  private val STORAGE = 0x80; private val COPY_DST = 0x08; private val COPY_SRC = 0x04; private val UNIFORM = 0x40
  private val MAP_READ = 0x01

  private def pipeline(code: String): js.Dynamic =
    device.createComputePipeline(literal(layout = "auto",
      compute = literal(module = device.createShaderModule(literal(code = code)), entryPoint = "main")))
  private val convP = pipeline(Kernels.conv); private val normP = pipeline(Kernels.norm)
  private val biasP = pipeline(Kernels.channelBias); private val poolP = pipeline(Kernels.pool)
  private val matP = pipeline(Kernels.matmul)

  private def buffer(floats: Int, usage: Int): js.Dynamic =
    device.createBuffer(literal(size = math.max(16, floats * 4), usage = usage))
  private def upload(a: Array[Float]): js.Dynamic =
    val b = buffer(a.length, STORAGE | COPY_DST)
    device.queue.writeBuffer(b, 0, a.toTypedArray)
    b
  // the weights, each array once, by the layer it belongs to
  private val weights = new java.util.IdentityHashMap[AnyRef, js.Dynamic]()
  private def of(a: Array[Float]): js.Dynamic =
    val got = weights.get(a)
    if got != null then got else { val b = upload(a); weights.put(a, b); b }
  private val noBias = upload(Array(0f, 0f, 0f, 0f))

  /** one dispatch: its pipeline, its bind group, its workgroups */
  private final case class Step(pipeline: js.Dynamic, group: js.Dynamic, threads: Int)
  private final class Plan(val batch: Int, val n: Int, val steps: Vector[Step], val inSp: js.Dynamic,
      val inGl: js.Dynamic, val policy: js.Dynamic, val pass: js.Dynamic, val value: js.Dynamic)
  private val plans = scala.collection.mutable.Map.empty[(Int, Int), Plan]

  private def uniform(words: Int*): js.Dynamic =
    val b = device.createBuffer(literal(size = 32, usage = UNIFORM | COPY_DST))
    val u = new Uint32Array(8); words.zipWithIndex.foreach((w, i) => u(i) = w)
    device.queue.writeBuffer(b, 0, u)
    b
  private def group(p: js.Dynamic, buffers: js.Dynamic*): js.Dynamic =
    device.createBindGroup(literal(layout = p.getBindGroupLayout(0),
      entries = js.Array(buffers.zipWithIndex.map((b, i) => literal(binding = i, resource = literal(buffer = b)))*)))

  private def plan(batch: Int, n: Int): Plan = plans.getOrElseUpdate((batch, n), {
    val a = n * n
    val steps = Vector.newBuilder[Step]
    def act(c: Int) = buffer(batch * c * a, STORAGE)
    def vec(c: Int) = buffer(batch * c, STORAGE | COPY_SRC)
    def conv(c: Conv, in: js.Dynamic, out: js.Dynamic, acc: Boolean): Unit =
      steps += Step(convP, group(convP, uniform(batch, n, c.in, c.out, c.ky, c.kx, if acc then 1 else 0), in, of(c.w),
        out), batch * c.out * a)
    def norm(b: Norm, in: js.Dynamic, out: js.Dynamic): Unit =
      steps += Step(normP, group(normP, uniform(batch, b.channels, a), in, of(b.scale), of(b.bias), out),
        batch * b.channels * a)
    def bias(c: Int, bias: js.Dynamic, t: js.Dynamic): Unit =
      steps += Step(biasP, group(biasP, uniform(batch, c, a), bias, t), batch * c * a)
    def pool(c: Int, in: js.Dynamic, out: js.Dynamic, value: Boolean): Unit =
      steps += Step(poolP, group(poolP, uniform(batch, c, a, if value then 1 else 0), in, out), batch * c)
    def mat(m: MatMul, in: js.Dynamic, out: js.Dynamic, b: Option[MatBias] = None, relu: Boolean = false): Unit =
      steps += Step(matP, group(matP, uniform(batch, m.in, m.out, (if b.isDefined then 1 else 0) | (if relu then 2
        else 0)), in, of(m.w), b.fold(noBias)(x => of(x.b)), out), batch * m.out)
    val C = model.conv1.out
    val inSp = buffer(batch * model.spatial * a, STORAGE | COPY_DST)
    val inGl = buffer(batch * model.global, STORAGE | COPY_DST)
    val t = act(C); val x = act(C); val mid = act(C); val x2 = act(C)
    val gb = vec(C)
    conv(model.conv1, inSp, t, acc = false); mat(model.ginput, inGl, gb); bias(C, gb, t)
    for b <- model.blocks do b match
      case Ordinary(n1, w1, n2, w2) =>
        norm(n1, t, x); conv(w1, x, mid, acc = false); norm(n2, mid, x2); conv(w2, x2, t, acc = true)
      case Pooling(n1, w1a, w1b, n1b, w1r, n2, w2) =>
        val reg = act(w1a.out); val gp = act(w1b.out); val gq = act(w1b.out); val rega = act(w1a.out)
        val pooled = vec(3 * w1b.out); val bv = vec(w1r.out)
        norm(n1, t, x); conv(w1a, x, reg, acc = false); conv(w1b, x, gp, acc = false); norm(n1b, gp, gq)
        pool(w1b.out, gq, pooled, value = false); mat(w1r, pooled, bv); bias(w1a.out, bv, reg)
        norm(n2, reg, rega); conv(w2, rega, t, acc = true)
    val trunk = act(C)
    norm(model.trunkNorm, t, trunk)
    // the policy head
    val p1o = act(model.p1.out); val g1o = act(model.g1.out); val g1a = act(model.g1.out)
    val gc = vec(3 * model.g1.out); val g2o = vec(model.g2.out); val p1a = act(model.p1.out)
    val policy = buffer(batch * a, STORAGE | COPY_SRC); val pass = vec(1)
    conv(model.p1, trunk, p1o, acc = false); conv(model.g1, trunk, g1o, acc = false); norm(model.g1Norm, g1o, g1a)
    pool(model.g1.out, g1a, gc, value = false); mat(model.g2, gc, g2o); bias(model.p1.out, g2o, p1o)
    norm(model.p1Norm, p1o, p1a); conv(model.p2, p1a, policy, acc = false); mat(model.pass, gc, pass)
    // the value head
    val v1o = act(model.v1.out); val v1a = act(model.v1.out); val vp = vec(3 * model.v1.out)
    val v2o = vec(model.v2.out); val value = vec(3)
    conv(model.v1, trunk, v1o, acc = false); norm(model.v1Norm, v1o, v1a); pool(model.v1.out, v1a, vp, value = true)
    mat(model.v2, vp, v2o, Some(model.v2b), relu = true); mat(model.v3, v2o, value, Some(model.v3b))
    Plan(batch, n, steps.result(), inSp, inGl, policy, pass, value)
  })

  /** the network on a batch of positions of one board size, each its inputs (spatial, global) */
  def run(n: Int, inputs: Vector[(Array[Float], Array[Float])]): Future[Vector[Raw]] =
    val batch = inputs.size; val a = n * n
    val p = plan(batch, n)
    val sp = new Float32Array(batch * model.spatial * a); val gl = new Float32Array(batch * model.global)
    inputs.zipWithIndex.foreach { case ((s, gg), b) =>
      sp.set(s.toTypedArray, b * model.spatial * a); gl.set(gg.toTypedArray, b * model.global)
    }
    device.queue.writeBuffer(p.inSp, 0, sp); device.queue.writeBuffer(p.inGl, 0, gl)
    val enc = device.createCommandEncoder()
    val pass = enc.beginComputePass()
    for s <- p.steps do
      pass.setPipeline(s.pipeline); pass.setBindGroup(0, s.group)
      val wg = (s.threads + 63) / 64
      if wg <= 65535 then pass.dispatchWorkgroups(wg) else pass.dispatchWorkgroups(65535, (wg + 65534) / 65535)
    pass.end()
    val readP = buffer(batch * a, MAP_READ | COPY_DST); val readS = buffer(batch, MAP_READ | COPY_DST)
    val readV = buffer(batch * 3, MAP_READ | COPY_DST)
    enc.copyBufferToBuffer(p.policy, 0, readP, 0, batch * a * 4)
    enc.copyBufferToBuffer(p.pass, 0, readS, 0, batch * 4)
    enc.copyBufferToBuffer(p.value, 0, readV, 0, batch * 3 * 4)
    device.queue.submit(js.Array(enc.finish()))
    def read(b: js.Dynamic): Future[Float32Array] =
      b.mapAsync(MAP_READ).asInstanceOf[js.Promise[Unit]].toFuture.map { _ =>
        val mapped = new Float32Array(b.getMappedRange().asInstanceOf[ArrayBuffer])
        val copy = new Float32Array(mapped.length); copy.set(mapped)
        b.unmap(); b.destroy(); copy
      }
    for pol <- read(readP); ps <- read(readS); v <- read(readV) yield
      (0 until batch).toVector.map { b =>
        Raw(Array.tabulate(a)(k => pol(b * a + k)), ps(b), Array.tabulate(3)(k => v(b * 3 + k)), Array.empty,
          Array.empty)
      }

object GpuNet:
  /** the network on the page's GPU, if it has WebGPU */
  def create(model: Model)(using ExecutionContext): Future[Option[GpuNet]] =
    val gpu = g.navigator.gpu
    if js.isUndefined(gpu) || gpu == null then Future.successful(None)
    else gpu.requestAdapter().asInstanceOf[js.Promise[js.Dynamic]].toFuture.flatMap { adapter =>
      if adapter == null then Future.successful(None)
      else
        val limits = adapter.limits
        adapter.requestDevice(literal(requiredLimits = literal(
          maxStorageBufferBindingSize = limits.maxStorageBufferBindingSize,
          maxBufferSize = limits.maxBufferSize))).asInstanceOf[js.Promise[js.Dynamic]].toFuture
          .map(d => Some(GpuNet(d, model)))
    }

  /** the model's bytes fetched from `url` and read */
  def load(url: String)(using ExecutionContext): Future[Model] =
    g.fetch(url).asInstanceOf[js.Promise[js.Dynamic]].toFuture
      .flatMap(r => r.arrayBuffer().asInstanceOf[js.Promise[ArrayBuffer]].toFuture)
      .map(ab => Binary.read(TypedArrayBuffer.wrap(ab)))
