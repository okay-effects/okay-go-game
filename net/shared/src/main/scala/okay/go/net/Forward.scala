package okay.go.net

/** WHAT THE NETWORK SAYS, raw, from the side to move's view: a logit for each point (row-major, the top row first)
 * and for passing; the value's three logits (win, loss, no result); the four score outputs; ownership before tanh */
final class Raw(val policy: Array[Float], val pass: Float, val value: Array[Float], val score: Array[Float],
    val ownership: Array[Float])

/**
 * THE FORWARD PASS, on the CPU, as KataGo's Eigen backend computes it for model version 8 on an n x n board (the
 * grid the board's own size, so the mask is every point): convolutions with zero padding; a batchnorm always with
 * its ReLU, its output masked; global pooling as mean, mean * (sqrt(area) - 14) * 0.1, max — and in the value head
 * mean, mean * (s - 14) * 0.1, mean * ((s - 14)^2 * 0.01 - 0.1). Tensors are [channel][y][x], floats.
 */
final class Forward(model: Model):
  def run(n: Int, spatial: Array[Float], global: Array[Float]): Raw =
    val area = n * n
    // a convolution as a product: the input unrolled (im2col: a row for each weight's (y, x, in), a column for each
    // point, zero beyond the edge), then out[oc][point] += w[row][oc] * col[row][point] — the inner loop the whole
    // board long
    def conv(c: Conv, in: Array[Float]): Array[Float] =
      val out = new Array[Float](c.out * area)
      val py = (c.ky - 1) / 2; val px = (c.kx - 1) / 2
      val rows = c.ky * c.kx * c.in
      val col = if c.ky == 1 && c.kx == 1 then in else
        val m = new Array[Float](rows * area)
        var dy = 0
        while dy < c.ky do
          var dx = 0
          while dx < c.kx do
            var ic = 0
            while ic < c.in do
              val row = ((dy * c.kx + dx) * c.in + ic) * area; val ib = ic * area
              var y = 0
              while y < n do
                val sy = y + dy - py
                if sy >= 0 && sy < n then
                  var x = 0
                  while x < n do
                    val sx = x + dx - px
                    if sx >= 0 && sx < n then m(row + y * n + x) = in(ib + sy * n + sx)
                    x += 1
                y += 1
              ic += 1
            dx += 1
          dy += 1
        m
      var r = 0
      while r < rows do
        val cb = r * area; val wb = r * c.out
        var oc = 0
        while oc < c.out do
          val wv = c.w(wb + oc); val ob = oc * area
          var p = 0
          while p < area do { out(ob + p) += wv * col(cb + p); p += 1 }
          oc += 1
        r += 1
      out
    def normed(b: Norm, in: Array[Float]): Array[Float] =
      val out = new Array[Float](in.length)
      var c = 0
      while c < b.channels do
        val s = b.scale(c); val o = b.bias(c); var i = c * area
        while i < (c + 1) * area do { val v = in(i) * s + o; out(i) = if v > 0f then v else 0f; i += 1 }
        c += 1
      out
    def matmul(m: MatMul, in: Array[Float]): Array[Float] =
      val out = new Array[Float](m.out)
      for ic <- 0 until m.in; oc <- 0 until m.out do out(oc) += in(ic) * m.w(ic * m.out + oc)
      out
    def addBias(t: Array[Float], b: Array[Float]): Unit =
      for c <- b.indices; i <- c * area until (c + 1) * area do t(i) += b(c)
    def mean(x: Array[Float], c: Int): Float =
      var s = 0f; var i = c * area
      while i < (c + 1) * area do { s += x(i); i += 1 }
      s / area
    val sqrtA = math.sqrt(area.toFloat).toFloat
    def pool(x: Array[Float], channels: Int): Array[Float] =
      val out = new Array[Float](3 * channels)
      for c <- 0 until channels do
        val m = mean(x, c)
        var mx = -1f; var i = c * area
        while i < (c + 1) * area do { if x(i) > mx then mx = x(i); i += 1 }
        out(c) = m; out(channels + c) = m * (sqrtA - 14f) * 0.1f; out(2 * channels + c) = mx
      out
    def valuePool(x: Array[Float], channels: Int): Array[Float] =
      val out = new Array[Float](3 * channels)
      for c <- 0 until channels do
        val m = mean(x, c)
        out(c) = m; out(channels + c) = m * (sqrtA - 14f) * 0.1f
        out(2 * channels + c) = m * ((sqrtA - 14f) * (sqrtA - 14f) * 0.01f - 0.1f)
      out

    // the trunk
    val t = conv(model.conv1, spatial)
    addBias(t, matmul(model.ginput, global))
    for b <- model.blocks do b match
      case Ordinary(n1, w1, n2, w2) =>
        val r = conv(w2, normed(n2, conv(w1, normed(n1, t))))
        var i = 0; while i < t.length do { t(i) += r(i); i += 1 }
      case Pooling(n1, w1a, w1b, n1b, w1r, n2, w2) =>
        val a = normed(n1, t)
        val reg = conv(w1a, a)
        val gq = normed(n1b, conv(w1b, a))
        addBias(reg, matmul(w1r, pool(gq, w1b.out)))
        val r = conv(w2, normed(n2, reg))
        var i = 0; while i < t.length do { t(i) += r(i); i += 1 }
    val trunk = normed(model.trunkNorm, t)

    // the policy head
    val p1 = conv(model.p1, trunk)
    val gc = pool(normed(model.g1Norm, conv(model.g1, trunk)), model.g1.out)
    addBias(p1, matmul(model.g2, gc))
    val policy = conv(model.p2, normed(model.p1Norm, p1))
    val pass = matmul(model.pass, gc)(0)

    // the value head
    val v1 = normed(model.v1Norm, conv(model.v1, trunk))
    val v2 = matmul(model.v2, valuePool(v1, model.v1.out))
    for i <- v2.indices do v2(i) = math.max(0f, v2(i) + model.v2b.b(i))
    val value = matmul(model.v3, v2); for i <- value.indices do value(i) += model.v3b.b(i)
    val score = matmul(model.sv3, v2); for i <- score.indices do score(i) += model.sv3b.b(i)
    val ownership = conv(model.own, v1)
    Raw(policy, pass, value, score, ownership)
