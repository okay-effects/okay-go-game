package okay.go.server

import okay.effects.json.Json
import okay.go.Room
import okay.go.Room.{Game, View}
import okay.scala3.*
import okay.scala3.std.Clock
import okay.serverless.Durable
import okay.serverless.cloud.*

/**
 * THE SERVER OF GAMES FAR APART (specs/go-game.md "Online"): each game a Durable Object, named by its id; its players
 * and watchers its WebSockets, tagged by seat. The first socket that brings both players' keys makes the game; from
 * then a socket's key is its seat — Black's, White's, or none: it watches. Every action is checked by the same rules
 * as the page's (`Room.act`) and kept in one step; then every socket is told the game, each with its own seat.
 */
object Games:
  val namespace = "GAMES"
  /** a game nobody has played or opened for this long is deleted by its alarm */
  val idle: Long = 30L * 24 * 60 * 60 * 1000

  /** the Worker: a socket at /games/<id>/socket goes to the game's object as it is */
  object Lobby extends App:
    override def objectFor(r: Request): Option[(String, String)] = r.path.split('/').toList match
      case List("", "games", id, "socket") if Room.wellFormed(id) => Some((namespace, id))
      case _ => None
    override def fetch(r: Request): Response ! Cloud =
      pure(if r.path == "/" then Response.text("okay-go-game: games at /games/<id>/socket")
        else Response.notFound())

  object Table extends Actor:
    private def load(name: String): Option[Game] ! InActor =
      Durable.step[ActorStep, InActor, Option[Json]](name)(Durable.get[Json, ActorStep]("game"))
        .map(_.flatMap(Room.read))
    private def keep(name: String, g: Game): Unit ! InActor =
      Durable.step[ActorStep, InActor, Unit](name)(Durable.put[Json, ActorStep]("game", Room.write(g)))
    /** the game used now: its alarm set for `idle` from now */
    private def touch(name: String): Unit ! InActor = Clock.now[InActor].flatMap(now =>
      Durable.step[ActorStep, InActor, Unit](name)(Durable.put[Long, ActorStep]("touched", now)
        .andThen(Durable.wakeAt[ActorStep](now + idle))))

    /** THE ALARM: a game with no socket open and untouched for `idle` deleted; else woken again later */
    override def alarm(name: String): Unit ! InActor = Clock.now[InActor].flatMap(now =>
      Sockets.open[InActor]().flatMap(open => Durable.step[ActorStep, InActor, Unit](name)(
        Durable.get[Long, ActorStep]("touched").flatMap { t =>
          val last = t.getOrElse(0L)
          if open.nonEmpty then Durable.wakeAt[ActorStep](now + idle)
          else if now - last >= idle then
            Durable.delete[ActorStep]("game").andThen(Durable.delete[ActorStep]("touched"))
          else Durable.wakeAt[ActorStep](last + idle)
        })))

    def fetch(name: String, request: Request): Response ! InActor =
      if !request.path.endsWith("/socket") then pure(Response.notFound())
      else load(name).flatMap { found =>
        val key = request.param("key").getOrElse("")
        val made: Option[Game] ! InActor = found match
          case Some(g) => pure(Some(g))
          case None => (request.param("black"), request.param("white")) match
            case (Some(b), Some(w)) if Room.wellFormed(b) && Room.wellFormed(w) && b != w && (key == b || key == w) =>
              val size = request.param("size").flatMap(_.toIntOption).filter(okay.go.Geometry.sizes.contains)
                .getOrElse(9)
              val g = Game(size, b, w, Vector.empty, None)
              keep(name, g).as(Some(g))
            case _ => pure(None)
        made.flatMap {
          case None => pure(Response.notFound("no such game"))
          case Some(g) => touch(name).andThen(Sockets.accept[InActor](request, Room.seatName(g.seat(key)))).flatMap {
            r => Sockets.later[InActor](tell(name, None)).as(r)
          }
        }
      }

    override def message(name: String, socket: Socket, text: String): Unit ! InActor =
      val seat = Room.seatOf(socket.tags.headOption.getOrElse(""))
      Room.readAction(text) match
        case None => refuse(name, socket, "not an action")
        case Some(a) => load(name).flatMap {
          case None => refuse(name, socket, "no such game")
          case Some(g) => Room.act(g, seat, a) match
            case Left(why) => refuse(name, socket, why)
            case Right(next) => keep(name, next).andThen(touch(name)).andThen(tell(name, None))
        }

    override def closed(name: String, socket: Socket): Unit ! InActor = tell(name, Some(socket))

    /** the game as `socket` sees it, with why its action was refused */
    private def refuse(name: String, socket: Socket, why: String): Unit ! InActor =
      load(name).flatMap(found => Sockets.open[InActor]().flatMap { all =>
        val g = found.getOrElse(Game(9, "", "", Vector.empty, None))
        Sockets.send[InActor](socket, Room.writeView(view(g, all, socket).copy(error = Some(why))))
      })

    private def view(g: Game, all: List[Socket], to: Socket): View =
      def here(seat: String) = all.exists(_.tags.contains(seat))
      View(g.size, g.moves, g.resigned, Room.seatOf(to.tags.headOption.getOrElse("")), here("black"), here("white"),
        all.count(_.tags.contains("watch")))

    /** every open socket (but `gone`) told the game, each its own seat */
    private def tell(name: String, gone: Option[Socket]): Unit ! InActor =
      load(name).flatMap {
        case None => pure(())
        case Some(g) => Sockets.open[InActor]().flatMap { open =>
          val all = open.filterNot(s => gone.exists(_.id == s.id))
          all.foldLeft(pure[Unit, InActor](()))((p, s) => p.andThen(Sockets.send[InActor](s,
            Room.writeView(view(g, all, s)))))
        }
      }

  val actors: Map[String, Actor] = Map(namespace -> Table)
