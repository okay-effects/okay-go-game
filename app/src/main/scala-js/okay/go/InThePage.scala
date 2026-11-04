package okay.go

import okay.business.scala3.*
import org.scalajs.dom
import scala.scalajs.js
import scala.scalajs.js.typedarray.Uint8Array

/** THE PAGE'S ADDRESS: its fragment (#g=…&k=…), written without a new entry in the history */
final class PageAddress extends Handles[Place]:
  def apply[R[_], Rest[_], A](p: A ! R)(using Split[Place, R, Rest]): A ! Rest =
    handle(p, Handler.Tail[Place, Rest, A, A](a => pure(a), [X] => (asked: Place[X]) => asked match
      case Place.Read() => Place.parse(dom.window.location.hash)
      case Place.Write(ps) =>
        val l = dom.window.location
        val to = l.pathname + l.search + (if ps.isEmpty then "" else "#" + Place.print(ps))
        dom.window.history.replaceState(null, "", to)
      case Place.Base() => dom.window.location.origin + dom.window.location.pathname + dom.window.location.search))

/**
 * THE TABLE OVER A WEBSOCKET to the games' server (`server`, wss://…): a socket to /games/<id>/socket with the key
 * (and, for a new game, both keys); what it says, numbered and passed to whoever listens; sent before the socket is
 * open, kept and sent when it opens; a socket that closes without a Leave opened again after a moment
 */
final class Sockets(server: String) extends Handles[Remote]:
  private var socket: Option[dom.WebSocket] = None
  private var address: Option[String] = None
  private var waiting = Vector.empty[String]
  private var heard = Vector.empty[Seen]
  private var listeners = Vector.empty[Seen => Unit]

  /** 16 random bytes as 32 hex digits */
  private def fresh(): String =
    val bytes = new Uint8Array(16)
    js.Dynamic.global.crypto.getRandomValues(bytes)
    (0 until 16).map(i => f"${bytes(i).toInt}%02x").mkString

  private def open(): Unit = address.foreach { url =>
    val ws = new dom.WebSocket(url)
    socket = Some(ws)
    ws.onopen = _ => { waiting.foreach(ws.send); waiting = Vector.empty }
    ws.onmessage = e => Room.readView(e.data.toString).foreach { v =>
      val s = Seen(heard.size + 1, v); heard :+= s; listeners.foreach(_(s))
    }
    ws.onclose = _ => if socket.contains(ws) then dom.window.setTimeout(() => if socket.contains(ws) then open(), 1500)
  }

  private def send(text: String): Unit = socket match
    case Some(ws) if ws.readyState == dom.WebSocket.OPEN => ws.send(text)
    case _ => waiting :+= text

  def apply[R[_], Rest[_], A](p: A ! R)(using Split[Remote, R, Rest]): A ! Rest =
    handle(p, Handler.Tail[Remote, Rest, A, A](a => pure(a), [X] => (asked: Remote[X]) => asked match
      case Remote.Create(colour) => Invite(fresh(), colour, fresh(), fresh())
      case Remote.Join(game, key, make) =>
        val query = (key.map("key" -> _).toList ++ make.toList.flatMap(i => List("black" -> i.black,
          "white" -> i.white))).map((k, v) => s"$k=$v").mkString("&")
        socket.foreach(_.close()); socket = None; heard = Vector.empty
        address = Some(s"$server/games/$game/socket" + (if query.isEmpty then "" else "?" + query))
        open()
      case Remote.Updates(after) => Events[Seen] { k =>
        val l: Seen => Unit = s => if s.n > after then k(s)
        listeners :+= l
        heard.lastOption.filter(_.n > after).foreach(s => dom.window.setTimeout(() => l(s), 0))
        () => listeners = listeners.filterNot(_ eq l)
      }
      case Remote.Send(action) => send(Room.writeAction(action))
      case Remote.Leave() =>
        val was = socket; socket = None; address = None; waiting = Vector.empty
        was.foreach(_.close())))

object Sockets:
  /** the games' server: `?server=` of the page's address (a local one: ws://127.0.0.1:8787), else the Worker */
  def server: String =
    val q = dom.URLSearchParams(dom.window.location.search)
    Option(q.get("server")).filter(_.nonEmpty).getOrElse("wss://okay-go-game.okay-effects.workers.dev")
