package okay.go.net

import java.nio.file.{Files, Path}
import java.util.zip.GZIPInputStream

/** THE PAGE'S WEIGHTS: the text model read and written as bytes (`Binary`) to nets/b10c128.bin, which the page's
 * build puts beside main.js. `sbt "netJVM/runMain okay.go.net.WriteBinary"` */
object WriteBinary:
  def main(args: Array[String]): Unit =
    val from = Path.of(args.headOption.getOrElse("nets/g170e-b10c128-s1141046784-d204142634.txt.gz"))
    val to = Path.of(args.lift(1).getOrElse("nets/b10c128.bin"))
    val model = Model.parse(String(GZIPInputStream(Files.newInputStream(from)).readAllBytes(), "UTF-8"))
    Files.write(to, Binary.write(model))
    println(s"$to: ${Files.size(to)} bytes, ${model.parameters} parameters")
