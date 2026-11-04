//> using scala 3.3.8
//> using dep com.microsoft.playwright:playwright:1.63.0

import com.microsoft.playwright.{Browser, BrowserType, Page, Playwright}
import com.microsoft.playwright.options.AriaRole
import java.nio.file.Path
import scala.jdk.CollectionConverters.*

/** THE PAGE END TO END in the installed Chromium (`$CHROME`, else Playwright's chromium-1234; never a download):
 * two players place stones and pass to the end; against the model, a move answered; pictures into the directory
 * given (default /tmp). `scala-cli run scripts/e2e.scala -- app/.js/target/web/index.html [pictures dir]` */
@main def e2e(page: String, pictures: String = "/tmp"): Unit =
  try run(page, pictures)
  catch case e: Throwable => { e.printStackTrace(); sys.exit(1) }

def run(page: String, pictures: String): Unit =
  val exe = sys.env.getOrElse("CHROME", sys.props("user.home") +
    "/Library/Caches/ms-playwright/chromium-1234/chrome-mac-arm64/" +
    "Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing")
  def fail(why: String): Nothing = { System.err.println("FAIL: " + why); sys.exit(1) }
  val pw = Playwright.create(Playwright.CreateOptions().setEnv(Map("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD" -> "1").asJava))
  val browser = pw.chromium().launch(BrowserType.LaunchOptions().setExecutablePath(Path.of(exe)))
  val p = browser.newContext(Browser.NewContextOptions().setViewportSize(520, 1000)).newPage()
  p.setDefaultTimeout(20000)
  val errors = scala.collection.mutable.ArrayBuffer.empty[String]
  p.onPageError(e => errors += e)
  p.navigate(Path.of(page).toAbsolutePath.toUri.toString)
  def button(name: String) = p.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName(name).setExact(true))
  // a point of the board is a link (a button at its slot)
  def point(name: String) = p.getByRole(AriaRole.LINK, Page.GetByRoleOptions().setName(name).setExact(true))
  def has(s: String) = p.waitForFunction(s"() => document.body.textContent.includes('$s')")
  def stones = p.locator("rect[class*='okay-mark-go-black'], rect[class*='okay-mark-go-white']").count()
  def shot(name: String) = p.screenshot(Page.ScreenshotOptions().setPath(Path.of(pictures, name)).setFullPage(true))
  button("Two players").click(); button("9 × 9 — a quick game").click()
  for (m, k) <- Seq("E5", "E4", "D4", "F5", "D5").zipWithIndex do
    point(m).click(); has(s"move ${k + 2}")
  if stones != 5 then fail(s"$stones stones, not 5")
  if point("E5").count() != 0 then fail("a taken point still has a button")
  shot("go-two.png")
  button("Pass").click(); has("move 7"); button("Pass").click(); has("Game over")
  if !p.locator("#app").textContent().matches("(?s).*(Black|White) wins by .*") then fail("no result")
  shot("go-end.png")
  button("Back to the menu").click()
  button("Play Black against the model").click(); button("9 × 9 — a quick game").click()
  for m <- Seq("E5", "C3", "G7") do
    if point(m).count() == 1 then
      val t0 = System.nanoTime(); point(m).click(); has("The model played")
      val said = "\\d+ games played out".r.findFirstIn(p.locator("#app").textContent()).getOrElse("")
      println(s"9 x 9 reply: ${(System.nanoTime() - t0) / 1000000} ms; $said")
  if stones < 4 then fail(s"$stones stones after three moves and replies")
  shot("go-model.png")
  // the full board against the model: its replies timed, how many games it played out
  button("Menu").click(); button("Play Black against the model").click()
  button("19 × 19 — the full board").click()
  var ms = List.empty[Long]
  for (m, k) <- Seq("D4", "Q16", "D16").zipWithIndex if point(m).count() == 1 do
    val t0 = System.nanoTime(); point(m).click(); has(s"move ${2 * k + 3}")
    ms :+= (System.nanoTime() - t0) / 1000000
  val thought = "\\d+ games played out".r.findFirstIn(p.locator("#app").textContent()).getOrElse(fail("no thought"))
  println(s"19 x 19 replies: ${ms.mkString(", ")} ms; $thought")
  shot("go-19.png")
  // Menu while the model thinks: the page answers at once, the search stopped
  point("Q4").click(); has("The model is thinking")
  val t1 = System.nanoTime(); button("Menu").click(); has("Two players")
  println(s"Menu while thinking: back in ${(System.nanoTime() - t1) / 1000000} ms")
  if errors.nonEmpty then fail("page errors: " + errors.mkString("; "))
  println("e2e: ok")
  browser.close(); pw.close()
