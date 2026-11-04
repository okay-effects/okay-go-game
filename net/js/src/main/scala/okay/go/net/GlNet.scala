package okay.go.net

import scala.concurrent.Future
import scala.scalajs.js
import scala.scalajs.js.Dynamic.global as g
import scala.scalajs.js.typedarray.*

/** A NETWORK ON A DEVICE of the page: WebGPU's (`GpuNet`) or WebGL2's (`GlNet`) — a batch of positions of one board
 * size in, the policy, the pass and the value out; what it runs on, and the bytes it holds */
trait OnDevice:
  def run(n: Int, inputs: Vector[(Array[Float], Array[Float])]): Future[Vector[Raw]]
  def api: String
  def adapter: String
  def f16: Boolean
  def weightBytes: Long
  def otherBytes: Long

/**
 * THE NETWORK ON WEBGL2, where WebGPU is missing (an iPhone XR's Safari: iOS 18): a tensor [batch, C, n, n] is a
 * float texture of four channels a texel — the group g of channels of point (x, y) of position b at (g·n + x, b·n + y);
 * a vector [batch, C] at (g, b). A layer is a fragment shader drawn into the next texture: a convolution reads its
 * input through the norm before it (scale, bias, ReLU; zero beyond the board) and adds, as it writes, a channel bias
 * of its position and the residual; pooling reads through its norm too; a matmul adds its bias and its ReLU. The
 * weights are textures of 4 x 4 blocks (a texel a row). The order is `Forward`'s, the numbers the same within float
 * rounding. Needs EXT_color_buffer_float (float render targets).
 */
final class GlNet private (gl: js.Dynamic, model: Model, val adapter: String) extends OnDevice:
  val api = "WebGL2"
  val f16 = false
  var weightBytes = 0L
  var otherBytes = 0L
  private val W = 2048 // the width of a weights' texture
  private val TEX = 0x0DE1; private val RGBA32F = 0x8814; private val RGBA = 0x1908; private val FLOAT = 0x1406
  private val NEAREST = 0x2600; private val FB = 0x8D40; private val COLOR0 = 0x8CE0

  private def texture(w: Int, h: Int, data: Float32Array, weights: Boolean): js.Dynamic =
    val t = gl.createTexture()
    gl.bindTexture(TEX, t)
    gl.texImage2D(TEX, 0, RGBA32F, w, h, 0, RGBA, FLOAT, data.asInstanceOf[js.Any])
    gl.texParameteri(TEX, 0x2801, NEAREST); gl.texParameteri(TEX, 0x2800, NEAREST)
    gl.texParameteri(TEX, 0x2802, 0x812F); gl.texParameteri(TEX, 0x2803, 0x812F)
    if weights then weightBytes += w * h * 16L else otherBytes += w * h * 16L
    t
  private val dummy = texture(1, 1, new Float32Array(4), weights = false)

  private val vertex = "#version 300 es\nvoid main() { vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2); " +
    "gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0); }"
  private val head = "#version 300 es\nprecision highp float;\nprecision highp int;\nprecision highp sampler2D;\n" +
    "out vec4 o;\nivec2 wAt(int i) { return ivec2(i % 2048, i / 2048); }\n" +
    "vec4 by(sampler2D w, int base, vec4 v) { return vec4(dot(texelFetch(w, wAt(base), 0), v), " +
    "dot(texelFetch(w, wAt(base + 1), 0), v), dot(texelFetch(w, wAt(base + 2), 0), v), " +
    "dot(texelFetch(w, wAt(base + 3), 0), v)); }\n"
  /** a convolution: flags 1 the input through a norm (NS: scales row 0, biases row 1), 2 a channel bias of the
   * position (B [batch, OG]), 4 the residual (R) */
  private val convCode = head + """
uniform sampler2D X; uniform sampler2D Wt; uniform sampler2D NS; uniform sampler2D B; uniform sampler2D R;
uniform int n; uniform int IG; uniform int k; uniform int flags;
void main() {
  ivec2 c = ivec2(gl_FragCoord.xy);
  int og = c.x / n; int x = c.x - og * n; int b = c.y / n; int y = c.y - b * n;
  int p = k / 2; int taps = k * k;
  vec4 acc = vec4(0.0);
  for (int t = 0; t < taps; t++) {
    int sy = y + t / k - p; int sx = x + t - (t / k) * k - p;
    if (sy < 0 || sy >= n || sx < 0 || sx >= n) continue;
    for (int ig = 0; ig < IG; ig++) {
      vec4 v = texelFetch(X, ivec2(ig * n + sx, b * n + sy), 0);
      if ((flags & 1) != 0) v = max(v * texelFetch(NS, ivec2(ig, 0), 0) + texelFetch(NS, ivec2(ig, 1), 0), 0.0);
      acc += by(Wt, ((og * taps + t) * IG + ig) * 4, v);
    }
  }
  if ((flags & 2) != 0) acc += texelFetch(B, ivec2(og, b), 0);
  if ((flags & 4) != 0) acc += texelFetch(R, c, 0);
  o = acc;
}"""
  /** global pooling of G groups, through a norm (flags 1); the value head's form (flags 2) */
  private val poolCode = head + """
uniform sampler2D X; uniform sampler2D NS; uniform int n; uniform int G; uniform int flags;
void main() {
  ivec2 c = ivec2(gl_FragCoord.xy);
  int which = c.x / G; int g = c.x - which * G; int b = c.y;
  vec4 s = vec4(0.0); vec4 m = vec4(-1.0);
  for (int y = 0; y < n; y++) for (int x = 0; x < n; x++) {
    vec4 v = texelFetch(X, ivec2(g * n + x, b * n + y), 0);
    if ((flags & 1) != 0) v = max(v * texelFetch(NS, ivec2(g, 0), 0) + texelFetch(NS, ivec2(g, 1), 0), 0.0);
    s += v; m = max(m, v);
  }
  vec4 mean = s / float(n * n); float d = float(n) - 14.0;
  if (which == 0) o = mean;
  else if (which == 1) o = mean * (d * 0.1);
  else if ((flags & 2) != 0) o = mean * (d * d * 0.01 - 0.1);
  else o = m;
}"""
  /** a matmul of a vector: flags 1 its bias (B), 2 its ReLU */
  private val matCode = head + """
uniform sampler2D X; uniform sampler2D Wt; uniform sampler2D B; uniform int IG; uniform int flags;
void main() {
  ivec2 c = ivec2(gl_FragCoord.xy);
  vec4 acc = vec4(0.0);
  for (int ig = 0; ig < IG; ig++) acc += by(Wt, (c.x * IG + ig) * 4, texelFetch(X, ivec2(ig, c.y), 0));
  if ((flags & 1) != 0) acc += texelFetch(B, ivec2(c.x, 0), 0);
  if ((flags & 2) != 0) acc = max(acc, 0.0);
  o = acc;
}"""

  private def program(code: String): js.Dynamic =
    def shader(kind: Int, src: String) =
      val s = gl.createShader(kind); gl.shaderSource(s, src); gl.compileShader(s)
      if !gl.getShaderParameter(s, 0x8B81).asInstanceOf[Boolean] then
        throw IllegalStateException("a shader: " + gl.getShaderInfoLog(s))
      s
    val p = gl.createProgram()
    gl.attachShader(p, shader(0x8B31, vertex)); gl.attachShader(p, shader(0x8B30, code)); gl.linkProgram(p)
    if !gl.getProgramParameter(p, 0x8B82).asInstanceOf[Boolean] then
      throw IllegalStateException("a program: " + gl.getProgramInfoLog(p))
    p
  private val convP = program(convCode); private val poolP = program(poolCode); private val matP = program(matCode)
  private val vao = gl.createVertexArray()
  private val fb = gl.createFramebuffer()

  private def groups(c: Int) = (c + 3) / 4
  /** weights as 4 x 4 blocks, a texel a row: block (o, t, i) of a conv, (o, i) of a matmul */
  private def blocks(og: Int, taps: Int, ig: Int)(w: (Int, Int, Int) => Float): js.Dynamic =
    val count = og * taps * ig * 4
    val h = (count + W - 1) / W
    val data = new Float32Array(W * h * 4)
    for o <- 0 until og * 4; t <- 0 until taps; i <- 0 until ig * 4 do
      val texel = (((o / 4) * taps + t) * ig + i / 4) * 4 + o % 4
      data(texel * 4 + i % 4) = w(o, t, i)
    texture(W, h, data, weights = true)
  private def convW(c: Conv): js.Dynamic =
    blocks(groups(c.out), c.ky * c.kx, groups(c.in)) { (o, t, i) =>
      if o < c.out && i < c.in then c.w((t * c.in + i) * c.out + o) else 0f
    }
  private def matW(m: MatMul): js.Dynamic =
    blocks(groups(m.out), 1, groups(m.in))((o, _, i) => if o < m.out && i < m.in then m.w(i * m.out + o) else 0f)
  private def normW(b: Norm): js.Dynamic =
    val gs = groups(b.channels); val data = new Float32Array(gs * 4 * 2)
    for c <- 0 until b.channels do { data(c) = b.scale(c); data(gs * 4 + c) = b.bias(c) }
    texture(gs, 2, data, weights = true)
  private def biasW(b: MatBias): js.Dynamic =
    val data = new Float32Array(groups(b.channels) * 4)
    for c <- 0 until b.channels do data(c) = b.b(c)
    texture(groups(b.channels), 1, data, weights = true)
  private val made = new java.util.IdentityHashMap[AnyRef, js.Dynamic]()
  private def once(key: AnyRef)(make: => js.Dynamic): js.Dynamic =
    val got = made.get(key)
    if got != null then got else { val t = make; made.put(key, t); t }

  /** a pass: its program, its output texture and size, its uniforms and its textures by name */
  private final class Pass(val p: js.Dynamic, val out: js.Dynamic, val w: Int, val h: Int,
      val ints: Seq[(String, Int)], val textures: Seq[(String, js.Dynamic)])
  private final class Plan(val passes: Vector[Pass], val inSp: js.Dynamic, val inGl: js.Dynamic,
      val policy: js.Dynamic, val pass: js.Dynamic, val value: js.Dynamic)
  private val plans = scala.collection.mutable.Map.empty[(Int, Int), Plan]

  private def plan(batch: Int, n: Int): Plan = plans.getOrElseUpdate((batch, n), {
    val passes = Vector.newBuilder[Pass]
    def act(c: Int) = texture(groups(c) * n, batch * n, null, weights = false)
    def vec(c: Int) = texture(groups(c), batch, null, weights = false)
    def conv(c: Conv, in: js.Dynamic, out: js.Dynamic, norm: Option[Norm] = None, bias: Option[js.Dynamic] = None,
        res: Option[js.Dynamic] = None): Unit =
      require(c.ky == c.kx, "a square kernel")
      passes += Pass(convP, out, groups(c.out) * n, batch * n,
        Seq("n" -> n, "IG" -> groups(c.in), "k" -> c.ky, "flags" -> ((if norm.isDefined then 1 else 0) |
          (if bias.isDefined then 2 else 0) | (if res.isDefined then 4 else 0))),
        Seq("X" -> in, "Wt" -> once(c)(convW(c)), "NS" -> norm.fold(dummy)(b => once(b)(normW(b))),
          "B" -> bias.getOrElse(dummy), "R" -> res.getOrElse(dummy)))
    def pool(c: Int, in: js.Dynamic, out: js.Dynamic, norm: Norm, value: Boolean): Unit =
      require(c % 4 == 0, "pooled channels four by four")
      passes += Pass(poolP, out, 3 * groups(c), batch, Seq("n" -> n, "G" -> groups(c), "flags" -> (1 |
        (if value then 2 else 0))), Seq("X" -> in, "NS" -> once(norm)(normW(norm))))
    def mat(m: MatMul, in: js.Dynamic, out: js.Dynamic, b: Option[MatBias] = None, relu: Boolean = false): Unit =
      passes += Pass(matP, out, groups(m.out), batch, Seq("IG" -> groups(m.in), "flags" -> ((if b.isDefined then 1
        else 0) | (if relu then 2 else 0))), Seq("X" -> in, "Wt" -> once(m)(matW(m)),
        "B" -> b.fold(dummy)(x => once(x)(biasW(x)))))
    val C = model.conv1.out
    val inSp = act(model.spatial); val inGl = vec(model.global)
    // the trunk: t and u in turn (a pass may not read the texture it writes)
    var t = act(C); var u = act(C); val mid = act(C)
    val gb = vec(C)
    mat(model.ginput, inGl, gb); conv(model.conv1, inSp, t, bias = Some(gb))
    for b <- model.blocks do
      b match
        case Ordinary(n1, w1, n2, w2) =>
          conv(w1, t, mid, norm = Some(n1)); conv(w2, mid, u, norm = Some(n2), res = Some(t))
        case Pooling(n1, w1a, w1b, n1b, w1r, n2, w2) =>
          val gp = act(w1b.out); val pooled = vec(3 * w1b.out); val bv = vec(w1r.out); val reg = act(w1a.out)
          conv(w1b, t, gp, norm = Some(n1)); pool(w1b.out, gp, pooled, n1b, value = false); mat(w1r, pooled, bv)
          conv(w1a, t, reg, norm = Some(n1), bias = Some(bv)); conv(w2, reg, u, norm = Some(n2), res = Some(t))
      val s = t; t = u; u = s
    // the policy head (the trunk through its norm)
    val tn = Some(model.trunkNorm)
    val g1o = act(model.g1.out); val gc = vec(3 * model.g1.out); val g2o = vec(model.g2.out)
    val p1o = act(model.p1.out); val policy = act(1); val pass = vec(1)
    conv(model.g1, t, g1o, norm = tn); pool(model.g1.out, g1o, gc, model.g1Norm, value = false); mat(model.g2, gc, g2o)
    conv(model.p1, t, p1o, norm = tn, bias = Some(g2o)); conv(model.p2, p1o, policy, norm = Some(model.p1Norm))
    mat(model.pass, gc, pass)
    // the value head
    val v1o = act(model.v1.out); val vp = vec(3 * model.v1.out); val v2o = vec(model.v2.out); val value = vec(3)
    conv(model.v1, t, v1o, norm = tn); pool(model.v1.out, v1o, vp, model.v1Norm, value = true)
    mat(model.v2, vp, v2o, Some(model.v2b), relu = true); mat(model.v3, v2o, value, Some(model.v3b))
    Plan(passes.result(), inSp, inGl, policy, pass, value)
  })

  private def draw(p: Pass): Unit =
    gl.bindFramebuffer(FB, fb)
    gl.framebufferTexture2D(FB, COLOR0, TEX, p.out, 0)
    gl.viewport(0, 0, p.w, p.h)
    gl.useProgram(p.p)
    for (name, v) <- p.ints do gl.uniform1i(gl.getUniformLocation(p.p, name), v)
    for ((name, tex), unit) <- p.textures.zipWithIndex do
      gl.activeTexture(0x84C0 + unit); gl.bindTexture(TEX, tex)
      gl.uniform1i(gl.getUniformLocation(p.p, name), unit)
    gl.bindVertexArray(vao)
    gl.drawArrays(0x0004, 0, 3)
    for unit <- p.textures.indices do { gl.activeTexture(0x84C0 + unit); gl.bindTexture(TEX, null) }

  private def read(tex: js.Dynamic, w: Int, h: Int): Float32Array =
    gl.bindFramebuffer(FB, fb)
    gl.framebufferTexture2D(FB, COLOR0, TEX, tex, 0)
    val out = new Float32Array(w * h * 4)
    gl.readPixels(0, 0, w, h, RGBA, FLOAT, out)
    out

  /** a batch, in a task of its own (a timer's): WebGL reads back at once, and a search of batches one after another
   * must leave the page its turns — to draw, to hear a click */
  def run(n: Int, inputs: Vector[(Array[Float], Array[Float])]): Future[Vector[Raw]] =
    val done = scala.concurrent.Promise[Vector[Raw]]()
    val _ = js.timers.setTimeout(0)(done.completeWith(now(n, inputs)))
    done.future

  private def now(n: Int, inputs: Vector[(Array[Float], Array[Float])]): Future[Vector[Raw]] =
    try
      val batch = inputs.size; val a = n * n
      val p = plan(batch, n)
      // the inputs as textures: spatial channel c of point (x, y) of position b at texel (c / 4 · n + x, b · n + y)
      val sg = groups(model.spatial); val gg = groups(model.global)
      val sp = new Float32Array(sg * n * batch * n * 4); val gl0 = new Float32Array(gg * batch * 4)
      for b <- 0 until batch do
        val (s, glob) = inputs(b)
        for c <- 0 until model.spatial; y <- 0 until n; x <- 0 until n do
          sp((((b * n + y) * sg * n) + (c / 4) * n + x) * 4 + c % 4) = s(c * a + y * n + x)
        for c <- 0 until model.global do gl0((b * gg + c / 4) * 4 + c % 4) = glob(c)
      gl.bindTexture(TEX, p.inSp); gl.texSubImage2D(TEX, 0, 0, 0, sg * n, batch * n, RGBA, FLOAT, sp)
      gl.bindTexture(TEX, p.inGl); gl.texSubImage2D(TEX, 0, 0, 0, gg, batch, RGBA, FLOAT, gl0)
      p.passes.foreach(draw)
      val pol = read(p.policy, n, batch * n); val ps = read(p.pass, 1, batch); val v = read(p.value, 1, batch)
      Future.successful((0 until batch).toVector.map { b =>
        Raw(Array.tabulate(a)(k => pol(((b * n + k / n) * n + k % n) * 4)), ps(b * 4),
          Array.tabulate(3)(k => v(b * 4 + k)), Array.empty, Array.empty)
      })
    catch case e: Throwable => Future.failed(e)

object GlNet:
  /** the network on WebGL2 — or why not: no WebGL2, no float render targets, a shader refused */
  def create(model: Model): Either[String, GlNet] =
    try
      val canvas = g.document.createElement("canvas")
      val gl = canvas.getContext("webgl2")
      if gl == null || js.isUndefined(gl) then Left("this browser has no WebGL2 either")
      else if gl.getExtension("EXT_color_buffer_float") == null then
        Left("WebGL2 here has no float render targets (EXT_color_buffer_float)")
      else if gl.getParameter(0x0D33).asInstanceOf[Int] < 2048 then Left("WebGL2's textures are too small here")
      else
        val info = gl.getExtension("WEBGL_debug_renderer_info")
        val name = if info == null then gl.getParameter(0x1F01).toString
          else gl.getParameter(info.UNMASKED_RENDERER_WEBGL).toString
        Right(GlNet(gl, model, name))
    catch case e: Throwable => Left(s"WebGL2 failed: ${e.getMessage}")
