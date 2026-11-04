package okay.go.server

import okay.serverless.host.{Host, HostConfig}
import java.nio.file.{Files, Paths}

/** THE GAMES ON A SERVER OF ONE'S OWN (okay-serverless' host): the fallback beside Cloudflare, the same lobby and
 * objects, the state in one directory; the page served too when it is built (app/.js/target/web). Settings:
 * `OKAY_GO_DIR` (go-data), `PORT` (8787), `HOST` (127.0.0.1) */
object Serve:
  def main(args: Array[String]): Unit =
    val env = sys.env
    Host.start(Games.Lobby, Games.actors, HostConfig(
      dir = Paths.get(env.getOrElse("OKAY_GO_DIR", "go-data")),
      port = env.get("PORT").map(_.toInt).getOrElse(8787),
      host = env.getOrElse("HOST", "127.0.0.1"),
      static = Some(Paths.get("app", ".js", "target", "web")).filter(Files.isDirectory(_))))
    Thread.currentThread.join()
