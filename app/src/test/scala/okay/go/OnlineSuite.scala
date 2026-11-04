package okay.go

import okay.business.scala3.*
import okay.go.Go.App

/** A GAME FAR APART, in memory: two people's programs and a watcher's, one table between them (`Tables`, the
 * server's rules); what the server says is given to each screen as its update, as the page's face would */
class OnlineSuite extends munit.FunSuite:
  /** a step taken on to the next screen that waits */
  def on(step: Step[Unit]): Step[Unit] = step match
    case Step.Working(_, next) => on(next())
    case Step.Marked(_, _, next) => on(next())
    case Step.Leavable(_, next, _) => on(next())
    case other => other
  def screen(step: Step[Unit]): Screen[?] = step match
    case w: Step.Waiting[?, ?] => w.screen
    case other => fail(s"not waiting: $other")
  def press(step: Step[Unit], label: String): Step[Unit] = step match
    case w: Step.Waiting[Unit, s] =>
      val i = w.screen.buttons.indexWhere(_.label == label)
      assert(i >= 0, s"no '$label' on '${w.screen.title}': ${w.screen.buttons.map(_.label).mkString(", ")}")
      on(w.answer(Answer(i)).fold(ps => fail(ps.toString), identity))
    case other => fail(s"not waiting: $other")
  /** what the client heard last, given to its screen as the update it waits for */
  def hear(step: Step[Unit], c: Tables#Client): Step[Unit] = step match
    case w: Step.Waiting[Unit, s] if w.screen.events.isDefined =>
      on(w.resume(Online.Act.Update(c.latest.get).asInstanceOf[s]))
    case other => fail(s"not listening: $other")
  def texts(step: Step[Unit]): List[String] = screen(step).parts.flatMap {
    case Part.Text(t) => List(t)
    case Part.Notice(_, t) => List(t)
    case Part.Section(_, ps) => ps.collect { case Part.Text(t) => t }
    case _ => Nil
  }
  /** what the programs copied, newest last */
  val copied: scala.collection.mutable.Buffer[String] = scala.collection.mutable.Buffer.empty
  def program(t: Tables#Client, place: Handles[Place]): Step[Unit] =
    on(steps(keptIn(scala.collection.mutable.Map.empty[String, String])(copiedTo(copied)(place(t(
      Model.searching(new scala.util.Random(1), 50)(Go.main[App])))))))

  test("a game far apart: made by Black, joined by the friend's link, watched; moves seen by all; a resignation") {
    val tables = Tables()
    val a = tables.client(); val placeA = Place.fixed()
    var black = press(press(press(program(a, placeA), "Play a friend online"), "9 × 9 — a quick game"), "I play Black")
    black = hear(black, a)
    assertEquals(screen(black).title, "Go online")
    assert(texts(black).exists(_.startsWith("You play Black · Black to play")), texts(black).toString)
    assert(texts(black).contains("Black here · White away"), texts(black).toString)
    // the page's address is the creator's own seat; the friend's link is shown
    val fragment = placeA.now
    val friendLink = texts(black).find(t => t.contains("&k=") && !t.contains(fragment("k"))).get
    val b = tables.client()
    var white = hear(program(b, Place.fixed(Place.parse(friendLink.dropWhile(_ != '#')))), b)
    assert(texts(white).exists(_.startsWith("You play White · Black to play — waiting")), texts(white).toString)
    assertEquals(screen(white).buttons.map(_.label), List("Resign", "Copy the watchers' link", "Leave"))
    val c = tables.client()
    val watcher = hear(program(c, Place.fixed(Map("g" -> fragment("g")))), c)
    assertEquals(screen(watcher).buttons.map(_.label), List("Copy the watchers' link", "Leave"))
    // Black's move: sent, then seen by everyone
    black = hear(press(hear(black, a), "E5"), a)
    white = hear(white, b)
    assert(screen(white).buttons.exists(_.label == "D4") && !screen(white).buttons.exists(_.label == "E5"))
    assert(texts(white).exists(_.startsWith("You play White · White to play")), texts(white).toString)
    assert(!screen(black).buttons.exists(_.label == "D4"), "not Black's turn")
    assert(texts(hear(watcher, c)).exists(_.contains("1 watching")))
    // White resigns, after a confirmation
    white = hear(press(press(white, "Resign"), "Yes"), b)
    assertEquals(screen(white).title, "Game over")
    assert(texts(hear(black, a)).contains("White resigns: Black wins"), texts(hear(black, a)).toString)
    assertEquals(tables.game(fragment("g")).map(_.resigned), Some(Some(Rules.White)))
  }

  test("a move refused by the server comes back with its reason; leaving clears the address") {
    val tables = Tables()
    val a = tables.client(); val place = Place.fixed()
    var black = hear(press(press(press(program(a, place), "Play a friend online"), "9 × 9 — a quick game"), "I play White"), a)
    assert(texts(black).exists(_.startsWith("You play White · Black to play — waiting")))
    black = press(black, "Leave")
    assertEquals(screen(black).title, "Go")
    assertEquals(place.now, Map.empty[String, String])
  }

  test("the links copied by their buttons: the friend's while they have not come, the watchers' always") {
    val tables = Tables()
    val a = tables.client(); val place = Place.fixed()
    var black = hear(press(press(press(program(a, place), "Play a friend online"), "13 × 13"), "I play Black"), a)
    black = press(black, "Copy the friend's link")
    val g = place.now("g")
    assert(copied.last.startsWith(s"https://example.org/go/#g=$g&k=") && !copied.last.contains(place.now("k")))
    assert(texts(black).contains("The friend's link is copied: paste it in a message."), texts(black).toString)
    black = press(black, "Copy the watchers' link")
    assertEquals(copied.last, s"https://example.org/go/#g=$g")
    // the friend comes and a move is made: only the watchers' link is offered
    val b = tables.client()
    hear(program(b, Place.fixed(Place.parse(copied(copied.size - 2).dropWhile(_ != '#')))), b)
    black = hear(press(hear(black, a), "E5"), a)
    assert(!screen(black).buttons.exists(_.label == "Copy the friend's link"))
    assert(screen(black).buttons.exists(_.label == "Copy the watchers' link"))
  }

  /** a device of a browser whose games are in `settings`: its program, and its client of `tables` */
  def device(tables: Tables, place: FixedPlace, settings: scala.collection.mutable.Map[String, String])
      : (Step[Unit], Tables#Client) =
    val c = tables.client()
    (on(steps(keptIn(settings)(copiedTo(copied)(place(c(Model.searching(new scala.util.Random(1), 50)(
      Go.main[App]))))))), c)
  /** a kept game opening: the moves the server has, given to its waiting screen */
  def opened(step: Step[Unit], c: Tables#Client): Step[Unit] = step match
    case w: Step.Waiting[Unit, s] if w.screen.events.isDefined =>
      assertEquals(w.screen.title, "Go"); on(w.resume(Some(c.latest.get).asInstanceOf[s]))
    case w: Step.Waiting[?, ?] =>
      fail(s"not opening: ${w.screen.title} ${w.screen.parts} ${w.screen.buttons.map(_.label)}")
    case other => fail(s"not opening: $other")

  test("a game against the model kept on the server: its link opens it on another device where it stood") {
    val tables = Tables()
    val here = Place.fixed(); val mine = scala.collection.mutable.Map.empty[String, String]
    var a = press(press(device(tables, here, mine)._1, "Play Black against the model"), "9 × 9 — a quick game")
    assertEquals(here.now.keySet, Set("g", "k", "m"))
    a = press(a, "E5") // the model answers at once (a search of 50 games)
    val g = here.now("g"); val link = here.now
    assertEquals(tables.game(g).map(_.moves.size), Some(2))
    assert(texts(a).exists(_.startsWith("You play Black · Black to play · move 3")), texts(a).toString)
    assert(texts(a).exists(_.endsWith(Place.print(here.now))), "the game's own link shown")
    // another device, the same link: the moves the server keeps, Black to play
    val (b0, c) = device(tables, Place.fixed(here.now), scala.collection.mutable.Map.empty)
    var b = opened(b0, c)
    assert(texts(b).exists(_.startsWith("You play Black · Black to play · move 3")), texts(b).toString)
    b = press(b, "D4") // played there; the model answers there
    assertEquals(tables.game(g).map(_.moves.size), Some(4))
    // the first device's browser lists the game; its link continues it with all four moves
    a = press(a, "Menu")
    a = press(a, "Continue a game")
    assertEquals(screen(a).buttons.map(_.label), List("Against the model · you play Black · 9 × 9 · move 3", "Back"))
    assertEquals(here.now, Map.empty[String, String]) // Menu: the game left
    val (d0, d) = device(tables, Place.fixed(link), mine)
    val again = opened(d0, d)
    assert(texts(again).exists(_.startsWith("You play Black · Black to play · move 5")), texts(again).toString)
    // a resignation ends it: off the browser's list
    val over = press(again, "Resign")
    assertEquals(screen(over).title, "Game over")
    assertEquals(tables.game(g).flatMap(_.resigned), Some(Rules.Black))
    assert(!mine.getOrElse("games", "").contains(g), "a finished game is off the list")
  }

  test("two players at one screen kept: each seat's move sent with its key; opened elsewhere at the same turn") {
    val tables = Tables()
    val here = Place.fixed()
    var a = press(press(device(tables, here, scala.collection.mutable.Map.empty)._1, "Two players"), "13 × 13")
    assertEquals(here.now.keySet, Set("g", "k", "o"))
    a = press(press(press(a, "G7"), "C3"), "Pass")
    val g = here.now("g")
    assertEquals(tables.game(g).map(_.moves), Some(Vector(Geometry(13).point("G7").get,
      Geometry(13).point("C3").get, -1)))
    val (b0, c) = device(tables, Place.fixed(here.now), scala.collection.mutable.Map.empty)
    val b = opened(b0, c)
    assert(texts(b).exists(_.startsWith("White to play · move 4")), texts(b).toString)
    assert(!screen(b).buttons.exists(_.label == "G7"), "a taken point has no button")
  }

  /** what a device's client heard last, given to its game's screen, which listens to the server */
  def heardGame(step: Step[Unit], c: Tables#Client): Step[Unit] = toldOf(step, c.latest.get)
  def toldOf(step: Step[Unit], seen: Seen): Step[Unit] = step match
    case w: Step.Waiting[Unit, s] if w.screen.events.isDefined => on(w.resume(Go.Act.Update(seen).asInstanceOf[s]))
    case other => fail(s"not listening: $other")

  test("one game open on two devices at once: a move on one is seen on the other; the model thinks where it moved") {
    val tables = Tables()
    val here = Place.fixed()
    val (a0, ca) = device(tables, here, scala.collection.mutable.Map.empty)
    var a = press(press(a0, "Play Black against the model"), "9 × 9 — a quick game")
    val (b0, cb) = device(tables, Place.fixed(here.now), scala.collection.mutable.Map.empty)
    var b = opened(b0, cb)
    a = press(a, "E5") // the model answers on this device
    val g = here.now("g")
    assertEquals(tables.game(g).map(_.moves.size), Some(2))
    // the other device hears the person's move: the model's turn, thought there — this one waits
    b = toldOf(b, cb.seen.find(_.view.moves.size == 1).get)
    assert(texts(b).contains("The model is thinking on the other device…"), texts(b).toString)
    assertEquals(screen(b).buttons.map(_.label), List("Think here", "Menu"))
    // ...then hears the model's move: Black to play here, with both stones
    b = heardGame(b, cb)
    assert(texts(b).exists(_.startsWith("You play Black · Black to play · move 3")), texts(b).toString)
    assert(texts(b).contains("A move was made on another device."), texts(b).toString)
    // played on this one now: the first device hears it and its model reply
    b = press(b, "D4")
    assertEquals(tables.game(g).map(_.moves.size), Some(4))
    a = heardGame(heardGame(a, ca), ca)
    assert(texts(a).exists(_.startsWith("You play Black · Black to play · move 5")), texts(a).toString)
    // both press a point at once: the second refused by the server, its page set to the game as it stands
    a = press(a, "C3"); b = press(b, "G7")
    b = heardGame(b, cb)
    assert(texts(b).exists(_.startsWith("Not played here")), texts(b).toString)
    assertEquals(tables.game(g).map(_.moves.size), Some(6))
    // a resignation on one ends it on the other
    a = press(a, "Resign")
    b = heardGame(b, cb)
    assertEquals(screen(b).title, "Game over")
  }
