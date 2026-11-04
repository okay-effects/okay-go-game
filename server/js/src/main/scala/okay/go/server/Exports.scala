package okay.go.server

import okay.serverless.cloudflare.Worker
import scala.scalajs.js.annotation.JSExportTopLevel

/** THE WORKER (Cloudflare): the lobby and the games' objects, by okay-serverless's `Worker` (server/worker/entry.js) */
object Exports:
  @JSExportTopLevel("worker")
  val worker: Worker = Worker(Games.Lobby, Games.actors)
