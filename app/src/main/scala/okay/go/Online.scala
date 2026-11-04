package okay.go

import okay.business.scala3.*
import okay.go.Room.{Action, View}

/** A NEW GAME FAR APART: its id, the creator's colour and key, the friend's key — and both keys, which the first
 * socket brings to make the game */
final case class Invite(game: String, colour: Int, black: String, white: String):
  def mine: String = if colour == Rules.Black then black else white
  def friend: String = if colour == Rules.Black then white else black

/** what the server said, numbered as it came */
final case class Seen(n: Int, view: View)

/** THE REMOTE TABLE OF A GAME FAR APART, as an effect: the program makes a game, sits at one, hears what happens, acts. Where
 * the game is — a server's object over a socket, or a test's memory — is a handler's */
enum Remote[X]:
  case Create(colour: Int) extends Remote[Invite]
  case Join(game: String, key: Option[String], make: Option[Invite]) extends Remote[Unit]
  /** what the server says after the `after`th thing it said: answered at once when that has come already */
  case Updates(after: Int) extends Remote[Events[Seen]]
  case Send(action: Action) extends Remote[Unit]
  case Leave() extends Remote[Unit]

/** THE PAGE'S ADDRESS, as an effect: a game's link is its fragment (#g=<id>&k=<key>) */
enum Place[X]:
  case Read() extends Place[Map[String, String]]
  case Write(params: Map[String, String]) extends Place[Unit]
  case Base() extends Place[String]

object Place:
  def fixed(params: Map[String, String] = Map.empty, base: String = "https://example.org/go/"): FixedPlace =
    FixedPlace(params, base)

  /** "g=…&k=…" as a map, and back */
  def parse(fragment: String): Map[String, String] = fragment.stripPrefix("#").split('&').toList.flatMap { kv =>
    kv.split('=').toList match
      case List(k, v) if k.nonEmpty => Some(k -> v)
      case _ => None
  }.toMap
  def print(ps: Map[String, String]): String = ps.map((k, v) => s"$k=$v").mkString("&")

/**
 * THE TABLE IN MEMORY, for tests: games kept here, played by `Room` as the server plays them; each handler one
 * client with its own seat, told every change at once
 */
final class Tables:
  private var games = Map.empty[String, Room.Game]
  private var clients = Vector.empty[Client]
  private var ids = 0
  def fresh(): String = { ids += 1; f"$ids%032x" }
  def game(id: String): Option[Room.Game] = games.get(id)
  private def tell(id: String): Unit =
    val here = clients.filter(_.game.contains(id))
    val g = games(id)
    def present(seat: Int) = here.exists(_.seat == seat)
    here.foreach(c => c.hear(View(g.moves, g.resigned, c.seat, present(Rules.Black), present(Rules.White),
      here.count(_.seat == Room.Watch))))
  def client(): Client = { val c = Client(); clients :+= c; c }

  final class Client extends Handles[Remote]:
    var game: Option[String] = None
    var seat: Int = Room.Watch
    var seen: Vector[Seen] = Vector.empty
    private var listeners = Vector.empty[Seen => Unit]
    def latest: Option[Seen] = seen.lastOption
    private[Tables] def hear(v: View): Unit =
      val s = Seen(seen.size + 1, v); seen :+= s; listeners.foreach(_(s))
    def apply[R[_], Rest[_], A](p: A ! R)(using Split[Remote, R, Rest]): A ! Rest =
      handle(p, Handler.Tail[Remote, Rest, A, A](a => pure(a), [X] => (asked: Remote[X]) => asked match
        case Remote.Create(colour) => Invite(fresh(), colour, fresh(), fresh())
        case Remote.Join(id, key, make) =>
          if !games.contains(id) then make.filter(i => key.contains(i.mine)).foreach(i =>
            games += id -> Room.Game(i.black, i.white, Vector.empty, None))
          games.get(id).foreach { g => game = Some(id); seat = g.seat(key.getOrElse("")); tell(id) }
        case Remote.Updates(after) => Events[Seen] { k =>
          val l: Seen => Unit = s => if s.n > after then k(s)
          listeners :+= l
          seen.lastOption.filter(_.n > after).foreach(k)
          () => listeners = listeners.filterNot(_ eq l)
        }
        case Remote.Send(a) => game.foreach { id =>
          Room.act(games(id), seat, a) match
            case Right(next) => games += id -> next; tell(id)
            case Left(why) =>
              val g = games(id)
              hear(View(g.moves, g.resigned, seat, true, true, 0, Some(why)))
        }
        case Remote.Leave() => game.foreach { id => game = None; tell(id) }))

/**
 * A GAME FAR APART, in okay-ui's words: the creator chooses a colour and gets two links — the friend's, which sits
 * them at the other colour, and the watchers'. The board is the server's: a person's move is sent, and the screen
 * waits for what the server says (`Screen.until` its updates); a watcher's board has no buttons.
 */
object Online:
  enum Act:
    case Update(s: Seen)
    case Stone(p: Int)
    case Copy(link: String, whose: String)
    case Pass, Resign, Leave

  def start[R[_]: Has[Ui]: Has[Remote]: Has[Place]: Has[Clipboard]]: Unit ! R =
    menu[Int, R]("Play a friend online", "I play Black" -> Rules.Black, "I play White" -> Rules.White,
      "Back" -> 0).flatMap {
      case 0 => pure(())
      case colour => perform[Remote](Remote.Create(colour)).flatMap(i => join[R](i.game, Some(i.mine), Some(i)))
    }

  /** sat at game `g` with `key` (none: watching), the game made if it is new (`made`); the page's address its link */
  def join[R[_]: Has[Ui]: Has[Remote]: Has[Place]: Has[Clipboard]](g: String, key: Option[String], made: Option[Invite]): Unit ! R =
    perform[Place](Place.Write(Map("g" -> g) ++ key.map("k" -> _))).andThen(
    perform[Remote](Remote.Join(g, key, made))).andThen(
    perform[Place](Place.Base())).flatMap(base => screen[R](base, g, made, None, sent = false, None))

  private def links(base: String, g: String, made: Option[Invite]): Part =
    val watch = s"$base#g=$g"
    Part.section("Links")((made.toList.flatMap(i => List(Part.text(s"Your friend plays ${Board.word(
      Rules.other(i.colour))}: send them this link"), Part.text(s"$base#g=$g&k=${i.friend}"))) ++
      List(Part.text("Anyone can watch with this one"), Part.text(watch)))*)

  def screen[R[_]: Has[Ui]: Has[Remote]: Has[Place]: Has[Clipboard]](base: String, g: String, made: Option[Invite],
      seen: Option[Seen], sent: Boolean, note: Option[Part]): Unit ! R =
    perform[Remote](Remote.Updates(seen.fold(0)(_.n))).flatMap { updates =>
      val leave = Button("Leave")(Act.Leave)
      val watchLink = s"$base#g=$g"
      // the links' buttons: the friend's while the friend has not come, the watchers' always
      def copies(friendToo: Boolean) = made.filter(_ => friendToo).toList.map(i =>
        Button("Copy the friend's link")(Act.Copy(s"$base#g=$g&k=${i.friend}", "friend's"))) :+
        Button("Copy the watchers' link")(Act.Copy(watchLink, "watchers'"))
      val shown: Screen[Act] = seen match
        case None => Screen[Act]("Go online")((List(Part.text("Connecting…"), links(base, g, made)) ++ note)*)(
          (copies(true) :+ leave)*)
        case Some(s) =>
          val v = s.view; val game = v.game; val pos = game.position
          val player = v.seat != Room.Watch
          val mine = player && !game.over && v.seat == pos.toPlay && !sent
          val legal = if mine then Rules.legal(pos) else Vector.empty
          val who = if player then s"You play ${Board.word(v.seat)}" else "You are watching"
          val state =
            if game.over then game.resigned.fold(Rules.result(pos.board))(c =>
              s"${Board.word(c)} resigns: ${Board.word(Rules.other(c))} wins")
            else s"${Board.word(pos.toPlay)} to play" + (if player && !mine then " — waiting" else "")
          def here(b: Boolean) = if b then "here" else "away"
          val present = s"Black ${here(v.black)} · White ${here(v.white)}" +
            (if v.watching > 0 then s" · ${v.watching} watching" else "")
          val inviting = !(v.black && v.white) || pos.moves == 0
          val parts = List(Board(pos, legal.toSet, areas = game.over && game.resigned.isEmpty),
            Part.text(s"$who · $state · move ${pos.moves + 1}"), Part.text(present)) ++
            v.error.map(Part.warning) ++ (if game.over then List(Part.info(state)) else Nil) ++ note ++
            List(links(base, g, made.filter(_ => inviting)))
          val acts = legal.map(p => Button(Rules.name(p))(Act.Stone(p)).at(s"p$p")) ++
            (if mine then Seq(Button("Pass")(Act.Pass)) else Nil) ++
            (if player && !game.over then Seq(Button("Resign", Style.Danger)(Act.Resign)) else Nil) ++
            copies(inviting) :+ leave
          Screen[Act](if game.over then "Game over" else "Go online")(parts*)(acts*)
      show(shown.until(updates.map(Act.Update(_)))).flatMap {
        case Act.Update(s) => defer(screen[R](base, g, made, Some(s), sent = false, note))
        case Act.Copy(link, whose) => copy[R](link).flatMap(done => defer(screen[R](base, g, made, seen, sent,
          Some(if done then Part.info(s"The $whose link is copied: paste it in a message.")
            else Part.warning(s"This browser does not let the page copy: copy the $whose link from the text.")))))
        case Act.Stone(p) => perform[Remote](Remote.Send(Action.Move(p))).andThen(
          defer(screen[R](base, g, made, seen, sent = true, None)))
        case Act.Pass => perform[Remote](Remote.Send(Action.Pass)).andThen(
          defer(screen[R](base, g, made, seen, sent = true, None)))
        case Act.Resign => confirm[R]("Resign this game?").flatMap(yes =>
          (if yes then perform[Remote](Remote.Send(Action.Resign)) else pure(())).andThen(
            defer(screen[R](base, g, made, seen, sent = yes, None))))
        case Act.Leave => perform[Remote](Remote.Leave()).andThen(perform[Place](Place.Write(Map.empty)))
      }
    }

/** THE ADDRESS IN MEMORY, for tests: its fragment's parameters as they are now */
final class FixedPlace(var now: Map[String, String], base: String) extends Handles[Place]:
  def apply[R[_], Rest[_], A](p: A ! R)(using Split[Place, R, Rest]): A ! Rest =
    handle(p, Handler.Tail[Place, Rest, A, A](a => pure(a), [X] => (asked: Place[X]) => asked match
      case Place.Read() => now
      case Place.Write(ps) => now = ps
      case Place.Base() => base))
