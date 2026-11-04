package okay.go.server

import okay.go.{Geometry, Room, Rules}
import okay.go.Room.Action
import okay.http.Request
import okay.serverless.cloud.MemoryCloud
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.*

/** THE GAMES' SERVER IN MEMORY: a game made by its first socket, seats by key, actions checked and told to all */
class GamesSuite extends munit.FunSuite:
  given ExecutionContext = ExecutionContext.global
  def await[A](f: Future[A]): A = Await.result(f, 10.seconds)
  val id = "0123456789abcdef0123456789abcdef"
  val black = "b" * 32; val white = "a" * 32; val other = "c" * 32

  /** the cloud's clock, moved by the tests */
  var now: Long = 1_800_000_000_000L
  def cloud(): MemoryCloud =
    val c = new MemoryCloud(() => now)
    Games.actors.foreach((ns, a) => c.actors(ns) = a)
    c
  def socket(c: MemoryCloud, query: String): c.MemorySocket =
    val r = await(c.serve(Games.Lobby)(Request("GET", s"/games/$id/socket?$query")))
    assertEquals(r.status, 101, r.body)
    val s = c.socketOf(Games.namespace, id, r)
    await(c.settled()); s
  def last(s: MemoryCloud#MemorySocket): Room.View = Room.readView(s.received.last).get
  def act(c: MemoryCloud, s: MemoryCloud#MemorySocket, a: Action): Unit =
    await(s.send(Room.writeAction(a))); await(c.settled())
  val e5 = Geometry(9).point("E5").get
  val d4 = Geometry(9).point("D4").get

  test("the first socket with both keys makes the game; each socket is told its seat; a watcher is counted") {
    val c = cloud()
    val b = socket(c, s"key=$black&black=$black&white=$white")
    assertEquals(last(b).seat, Rules.Black)
    val w = socket(c, s"key=$white")
    val v = socket(c, "")
    assertEquals(last(w).seat, Rules.White)
    assertEquals(last(v).seat, Room.Watch)
    assertEquals((last(b).black, last(b).white, last(b).watching), (true, true, 1))
  }

  test("a move told to everyone; out of turn, a watcher's, or illegal: refused to that socket with the reason") {
    val c = cloud()
    val b = socket(c, s"key=$black&black=$black&white=$white"); val w = socket(c, s"key=$white"); val v = socket(c, "")
    act(c, b, Action.Move(e5))
    for s <- Seq(b, w, v) do assertEquals(last(s).moves, Vector(e5))
    act(c, b, Action.Move(d4))
    assertEquals(last(b).error, Some("it is White's turn"))
    act(c, v, Action.Pass)
    assertEquals(last(v).error, Some("a watcher cannot play"))
    act(c, w, Action.Move(e5))
    assertEquals(last(w).error, Some("the point is taken"))
    act(c, w, Action.Move(d4)); act(c, b, Action.Pass); act(c, w, Action.Pass)
    assert(last(v).game.over)
    act(c, b, Action.Move(e5 + 1))
    assertEquals(last(b).error, Some("the game is over"))
  }

  test("a resignation ends it; the game kept: a socket opened later is told the same") {
    val c = cloud()
    val b = socket(c, s"key=$black&black=$black&white=$white")
    act(c, b, Action.Move(e5)); act(c, b, Action.Resign)
    val later = socket(c, s"key=$white")
    assertEquals((last(later).moves, last(later).resigned), (Vector(e5), Some(Rules.Black)))
  }

  test("no game without both keys; keys once set are not taken by another socket's") {
    val c = cloud()
    val r = await(c.serve(Games.Lobby)(Request("GET", s"/games/$id/socket?key=$other")))
    assertEquals(r.status, 404)
    socket(c, s"key=$black&black=$black&white=$white")
    val thief = socket(c, s"key=$other&black=$other&white=$white")
    assertEquals(last(thief).seat, Room.Watch)
  }

  test("a socket gone: the others are told who is still here") {
    val c = cloud()
    val b = socket(c, s"key=$black&black=$black&white=$white"); val w = socket(c, s"key=$white")
    await(w.close()); await(c.settled())
    assertEquals((last(b).black, last(b).white), (true, false))
  }

  test("a game nobody touches for 30 days is deleted by its alarm; one played or watched is kept") {
    val day = 24L * 60 * 60 * 1000
    val c = cloud()
    val b = socket(c, s"key=$black&black=$black&white=$white")
    act(c, b, Action.Move(e5))
    await(b.close()); await(c.settled())
    now += 20 * day
    assertEquals(await(c.fireAlarms()), 0, "not due yet")
    val w = socket(c, s"key=$white") // opened again: touched
    now += 25 * day
    assertEquals(await(c.fireAlarms()), 1)
    assert(c.stored(Games.namespace, id).contains("game"), "a socket is open: kept")
    await(w.close()); await(c.settled())
    now += 31 * day
    assertEquals(await(c.fireAlarms()), 1)
    assert(!c.stored(Games.namespace, id).contains("game"), c.stored(Games.namespace, id).toString)
    val r = await(c.serve(Games.Lobby)(Request("GET", s"/games/$id/socket?key=$white")))
    assertEquals(r.status, 404)
  }

  test("a game of 19 x 19: its size kept and told; an unknown size made a 9 x 9 one") {
    val c = cloud()
    val b = socket(c, s"key=$black&black=$black&white=$white&size=19")
    assertEquals(last(b).size, 19)
    val t1 = Geometry(19).point("T1").get
    act(c, b, Action.Move(t1))
    assertEquals(last(b).moves, Vector(t1))
    val other2 = "0" * 31 + "1"
    val r = await(c.serve(Games.Lobby)(Request("GET", s"/games/$other2/socket?key=$black&black=$black&white=$white&size=7")))
    assertEquals(r.status, 101)
  }
