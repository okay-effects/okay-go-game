//> using scala 3.3.8
//> using dep com.microsoft.playwright:playwright:1.63.0

import com.microsoft.playwright.{Browser, BrowserType, Page, Playwright}
import com.microsoft.playwright.options.AriaRole
import java.nio.file.Path
import scala.jdk.CollectionConverters.*

/** A GAME FAR APART, END TO END: three browsers of their own (Black, White by the friend's link, a watcher by the
 * watchers' link) on the page, against a games' server — `wrangler dev` (ws://127.0.0.1:8787) or the Worker (wss://…).
 * Moves seen by all, a reload keeps the seat, a resignation ends it; pictures into /tmp.
 * `scala-cli run scripts/e2e-online.scala -- <the page's URL, ?server=… in it if not the Worker>` */
@main def online(page: String): Unit =
  try run(page)
  catch case e: Throwable => { e.printStackTrace(); sys.exit(1) }

def run(page: String): Unit =
  val exe = sys.env.getOrElse("CHROME", sys.props("user.home") +
    "/Library/Caches/ms-playwright/chromium-1234/chrome-mac-arm64/" +
    "Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing")
  def fail(why: String): Nothing = { System.err.println("FAIL: " + why); sys.exit(1) }
  val pw = Playwright.create(Playwright.CreateOptions().setEnv(Map("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD" -> "1").asJava))
  val browser = pw.chromium().launch(BrowserType.LaunchOptions().setExecutablePath(Path.of(exe)))
  val errors = scala.collection.mutable.ArrayBuffer.empty[String]
  def person(): Page =
    val p = browser.newContext(Browser.NewContextOptions().setViewportSize(520, 1100)).newPage()
    p.setDefaultTimeout(20000); p.onPageError(e => errors += e); p
  def button(p: Page, n: String) = p.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName(n).setExact(true))
  def point(p: Page, n: String) = p.getByRole(AriaRole.LINK, Page.GetByRoleOptions().setName(n).setExact(true))
  def has(p: Page, s: String) = p.waitForFunction(s"() => document.body.textContent.includes(${"\""}$s${"\""})")
  def stones(p: Page) = p.locator("rect[class*='okay-mark-go-black'], rect[class*='okay-mark-go-white']").count()
  def url(p: Page, link: String) =
    if page.startsWith("file:") || page.startsWith("http") then link else link

  val black = person()
  black.navigate(page)
  button(black, "Play a friend online").click(); button(black, "I play Black").click()
  has(black, "Black here")
  val lines = black.locator("#app").innerText().linesIterator.map(_.trim).toVector
  val friend = lines.find(l => l.contains("#g=") && l.contains("&k=")).getOrElse(fail("no friend's link"))
  val watch = lines.find(l => l.contains("#g=") && !l.contains("&k=")).getOrElse(fail("no watchers' link"))
  println(s"friend: $friend\nwatch:  $watch")
  val white = person(); white.navigate(url(white, friend)); has(white, "You play White")
  val watcher = person(); watcher.navigate(url(watcher, watch)); has(watcher, "You are watching")
  has(black, "White here"); has(black, "1 watching")
  point(black, "E5").click()
  has(white, "White to play"); if stones(white) != 1 then fail("White does not see Black's stone")
  if point(black, "D4").count() != 0 then fail("Black has buttons out of turn")
  point(white, "D4").click()
  has(black, "Black to play · move 3"); has(watcher, "Black to play · move 3")
  if stones(watcher) != 2 then fail("the watcher does not see both stones")
  if point(watcher, "C3").count() != 0 then fail("a watcher has buttons")
  black.screenshot(Page.ScreenshotOptions().setPath(Path.of("/tmp/go-online-black.png")).setFullPage(true))
  watcher.screenshot(Page.ScreenshotOptions().setPath(Path.of("/tmp/go-online-watch.png")).setFullPage(true))
  // a reload keeps the seat: the address is the seat's link
  black.reload(); has(black, "You play Black · Black to play · move 3")
  point(black, "C3").click(); has(white, "White to play · move 4")
  button(white, "Resign").click(); button(white, "Yes").click()
  has(black, "White resigns: Black wins"); has(watcher, "White resigns: Black wins")
  watcher.screenshot(Page.ScreenshotOptions().setPath(Path.of("/tmp/go-online-end.png")).setFullPage(true))
  if errors.nonEmpty then fail("page errors: " + errors.mkString("; "))
  println("e2e online: ok")
  browser.close(); pw.close()
