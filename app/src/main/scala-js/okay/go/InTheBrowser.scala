package okay.go

import okay.business.faces.Skin
import okay.business.laminar.Laminar
import okay.scala3.std.Async
import org.scalajs.dom
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import okay.business.scala3.{+, Clipboard, Settings, Ui}


/** GO IN THE BROWSER: `sbt appJS/web`, then open app/.js/target/web/index.html — the screens on okay-faces' Laminar
 * face, the toy model in the page. The whole application is the line below */
object InTheBrowser:
  /** okay-ui's calm look in a wood brown, and the board: the picture's marks (the wood, the lines, the stones, the
   * last move, the owners), and over the picture the points' grid, a legal point's button a round shadow of the
   * stone to be played when the pointer is over it */
  val skin: Skin =
    val calm = Skin.calm(accent = "#8a5a2b", deep = "#4a2f14")
    // the points' grid of each size: inset by half a cell from the picture's edge, a column and a row a point
    val grids = Geometry.sizes.map { n =>
      val inset = f"${100.0 * (Board.margin - Board.cell / 2) / Board.side(n)}%.4f%%"
      s"""body .okay-mark-go [class$$="-$n"][class*="okay-mark-go-points"] { inset: $inset; }
         |body .okay-mark-go [class$$="-$n"][class*="okay-mark-go-points"] > .okay-group {
         |  grid-template-columns: repeat($n, minmax(0, 1fr)); grid-template-rows: repeat($n, minmax(0, 1fr)); }
         |""".stripMargin
    }.mkString
    calm.copy(css = calm.css + s"""
      |body > footer .bar { display: flex; flex-wrap: wrap; align-items: baseline; gap: 6px; }
      |body > footer .more { margin-left: auto; }
      |body .okay-mark-go { display: block; }
      |body .okay-mark-go > .okay-group { position: relative; width: min(100%, 640px); margin: 0 auto; padding: 0;
      |  gap: 0; border: 0; background: none; box-shadow: none; }
      |body .okay-mark-go .okay-picture-frame { margin: 0; max-width: none; }
      |body .okay-mark-go .okay-picture { border-radius: 6px; box-shadow: var(--ui-shadow); }
      |body .okay-mark-go-wood { fill: #dcb35c; }
      |body .okay-mark-go-line, body .okay-mark-go-star { fill: #3b2a14; }
      |body .okay-mark-go-star { rx: 4px; }
      |body .okay-mark-go-black { fill: #161616; rx: 18px; }
      |body .okay-mark-go-white { fill: #f8f6ef; stroke: #6b6b6b; stroke-width: 1px; rx: 18px; }
      |body .okay-mark-go-last-on-black, body .okay-mark-go-last-on-white { fill: #e03131; rx: 5px; }
      |body .okay-mark-go-area-black { fill: #161616; }
      |body .okay-mark-go-area-white { fill: #f8f6ef; stroke: #6b6b6b; stroke-width: 1px; }
      |body .okay-mark-go .okay-picture-label { font: 600 12px var(--okay-font); fill: #3b2a14; }
      |body .okay-mark-go [class*="okay-mark-go-points"] { position: absolute; display: block; }
      |body .okay-mark-go [class*="okay-mark-go-points"] > .okay-group { display: grid; height: 100%;
      |  gap: 0; margin: 0; padding: 0; border: 0; background: none; box-shadow: none; }
      |$grids      |body .okay-mark-go .okay-mark-pt { position: relative; display: block; min-width: 0; }
      |body .okay-mark-go .okay-mark-pt > .okay-group, body .okay-mark-go .okay-mark-pt .okay-mark-slot,
      |body .okay-mark-go .okay-mark-pt .okay-mark-slot > .okay-group, body .okay-mark-go .okay-mark-pt .okay-mark-button {
      |  position: absolute; inset: 0; display: block; margin: 0; padding: 0; border: 0; background: none;
      |  box-shadow: none; }
      |body .okay-mark-go .okay-mark-pt .okay-mark-button > .okay-ref { position: absolute; inset: 6%; display: block;
      |  min-height: 0; padding: 0; margin: 0; border: 0; border-radius: 50%; background: transparent;
      |  box-shadow: none; color: transparent; font-size: 0; cursor: pointer; }
      |body .okay-mark-go-points-black .okay-mark-button > .okay-ref:hover { background: rgba(22, 22, 22, 0.45); }
      |body .okay-mark-go-points-white .okay-mark-button > .okay-ref:hover { background: rgba(248, 246, 239, 0.8); }
      |""".stripMargin)

  /** the page's row: the application's, and okay-scala's `Async` — the model thinks into it */
  type Web[X] = (Async + Ui + Model + Remote + Place + Clipboard + Settings)[X]

  def main(args: Array[String]): Unit =
    if dom.window.location.search.contains("selftest") then Selftest.run(NetThinking.chosen) else page()

  def page(): Unit =
    // this browser's games in its local storage (under "go."); the links copied by the browser's clipboard; games
    // kept and far apart over a socket; the model a network on WebGPU or WebGL2 searched by PUCT (the search that
    // plays games out where neither is)
    val page = okay.business.web.InThePage
    val world = page.kept("go.")(page.clipboard(PageAddress()(Sockets(Sockets.server)(NetThinking(
      NetThinking.network(NetThinking.chosen))(Go.main[Web])))))
    val _ = Async.start(Laminar.run(dom.document.getElementById("app"), skin)(world))
