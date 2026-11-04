package okay.go

/** THE RULES' HASHES AND THE FAST SCAN AGAINST BOARDS: on thousands of positions of games at random (captures, kos,
 * passes), on every board, the legal points and the ko points as the boards themselves say — the test keeps every
 * board of the game and asks whether the board after a stone is one of them */
class ScanSuite extends munit.FunSuite:
  test("legal points and repeats, by the scan and by the rules, as the game's boards say") {
    var positions = 0; var kos = 0
    for size <- Seq(9, 13, 19); seed <- 1 to (if size == 9 then 20 else 6) do
      val r = new scala.util.Random(seed * 31 + size)
      var pos = Rules.startOf(size)
      var boards = Set(pos.board)
      while !pos.over && pos.moves < size * size * 2 do
        // the truth from boards: a stone legal but for superko, its board one of the game's
        val free = pos.copy(hashes = Set.empty)
        val byBoards = pos.geo.points.filter(p => Rules.tryPlay(free, p).isRight).toVector
        val repeats = byBoards.filter(p => boards(Rules.tryPlay(free, p).toOption.get._1))
        val legal = byBoards.filterNot(repeats.contains)
        assertEquals(Rules.legal(pos), legal, s"$size, game $seed, move ${pos.moves}: the rules")
        val scan = Scan(pos)
        assertEquals(scan.legal, legal, s"$size, game $seed, move ${pos.moves}: the scan")
        assertEquals(scan.repeats, repeats, s"$size, game $seed, move ${pos.moves}: ko")
        positions += 1; kos += repeats.size
        pos = if legal.isEmpty || r.nextInt(40) == 0 then Rules.pass(pos)
          else Rules.play(pos, legal(r.nextInt(legal.size)))
        boards += pos.board
    println(s"SCAN and the rules equal to the boards on $positions positions ($kos ko points among them)")
    assert(kos > 0, "kos happened")
  }
