/** okay-go-game: the game of Go in a page, on okay-ui's screens and okay-faces' Laminar face; a game between two
 * people far apart on a Cloudflare Worker, each game a Durable Object, on okay-serverless (specs/go-game.md) */
import sbtcrossproject.CrossPlugin.autoImport.{crossProject, CrossType}
import org.scalajs.linker.interface.ModuleKind

ThisBuild / organization := "io.github.okay-effects"
ThisBuild / version := "0.1.0"
ThisBuild / scalaVersion := "3.3.8"
ThisBuild / scalacOptions ++= Seq("-deprecation", "-feature", "-unchecked", "-Wunused:imports,privates,locals")
ThisBuild / resolvers ++= Seq("okay-ui" at "https://okay-effects.github.io/okay-ui/maven",
  "okay-faces" at "https://okay-effects.github.io/okay-faces/maven",
  "okay-simple" at "https://okay-effects.github.io/okay-simple/maven",
  "okay-scala" at "https://okay-effects.github.io/okay-scala/maven",
  "okay-http" at "https://okay-effects.github.io/okay-http/maven",
  "okay-mcp" at "https://okay-effects.github.io/okay-mcp/maven",
  "okay-serverless" at "https://okay-effects.github.io/okay-serverless/maven",
  "okay-store" at "https://okay-effects.github.io/okay-store/maven")

val okayUi = "0.1.0"
val serverless = "0.1.0"
val munit = Def.setting("org.scalameta" %%% "munit" % "1.2.3" % Test)
val web = taskKey[File]("the page: index.html and its script, linked, in app/.js/target/web")
val worker = taskKey[File]("the Worker's code, linked into server/worker/okay.js")

/** THE RULES AND A GAME'S ROOM, shared by the page and the server: pure, JSON its only dependency */
lazy val core = crossProject(JVMPlatform, JSPlatform).crossType(CrossType.Pure).in(file("core"))
  .settings(name := "okay-go-game-core", publish / skip := true,
    libraryDependencies ++= Seq("io.github.okay-effects" %%% "okay-effects-json" % "0.1.1", munit.value))

/** THE NETWORK: a KataGo network's reference on the CPU, its inputs, its search (specs/go-game.md, "A neural model") */
lazy val net = crossProject(JVMPlatform, JSPlatform).crossType(CrossType.Full).in(file("net"))
  .dependsOn(core)
  .settings(name := "okay-go-game-net", publish / skip := true, libraryDependencies += munit.value,
    Test / fork := true, Test / javaOptions += "-Xmx4g", Test / baseDirectory := (ThisBuild / baseDirectory).value)
  .jvmSettings(run / fork := true, run / baseDirectory := (ThisBuild / baseDirectory).value,
    run / javaOptions += "-Xmx4g")

/** THE NETWORK OF OUR OWN, TRAINED ON THE MAC: the v8 network in MLX (mlx-scala, its native libraries built by its
 * native-lib/build.sh; `-Dokm.lib` their path), a student distilled from KataGo's b10c128 (specs/go-game.md) */
val okmLib = sys.env.getOrElse("OKM_LIB",
  sys.props("user.home") + "/work/my/mlx-scala/native-lib/out/lib/libokm.dylib")
lazy val train = (project in file("train"))
  .dependsOn(net.jvm)
  .settings(name := "okay-go-game-train", publish / skip := true,
    resolvers += "mlx-scala" at "https://sergey-scherbina.github.io/mlx-scala/maven",
    libraryDependencies ++= Seq("io.github.sergey-scherbina" %% "mlx-scala" % "0.1.0", munit.value),
    fork := true, run / baseDirectory := (ThisBuild / baseDirectory).value,
    Test / baseDirectory := (ThisBuild / baseDirectory).value,
    javaOptions ++= Seq("--enable-native-access=ALL-UNNAMED", s"-Dokm.lib=$okmLib", "-Xmx12g", "-Xss16m"))

/** THE PAGE: okay-ui's screens on okay-faces' Laminar face */
lazy val app = crossProject(JVMPlatform, JSPlatform).crossType(CrossType.Pure).in(file("app"))
  .dependsOn(core, net)
  .settings(name := "okay-go-game", publish / skip := true,
    libraryDependencies ++= Seq("io.github.okay-effects" %%% "okay-ui" % okayUi, munit.value))
  .jsSettings(
    Compile / unmanagedSourceDirectories += baseDirectory.value.getParentFile / "src" / "main" / "scala-js",
    scalaJSUseMainModuleInitializer := true,
    Compile / mainClass := Some("okay.go.InTheBrowser"),
    web := {
      val linked = (Compile / fullLinkJS).value.data.publicModules.head.jsFileName
      val from = (Compile / fullLinkJS / scalaJSLinkerOutputDirectory).value / linked
      val out = target.value / "web"
      IO.copyFile(baseDirectory.value.getParentFile / "web" / "index.html", out / "index.html")
      IO.copyFile(from, out / "main.js")
      // the network's weights, written by `netJVM/runMain okay.go.net.WriteBinary`, beside the script
      for (name <- Seq("b10c128", "own")) {
        val weights = (ThisBuild / baseDirectory).value / "nets" / s"$name.bin"
        if (weights.exists) IO.copyFile(weights, out / "net" / s"$name.bin")
      }
      streams.value.log.info(s"the page: ${out / "index.html"}")
      out
    })

/** THE SERVER: a game a Durable Object (okay-serverless' `Actor`), its players and watchers its WebSockets — on
 * Cloudflare (JS, the main platform), and on okay-serverless' host (the JVM, the fallback), the same code */
lazy val server = crossProject(JVMPlatform, JSPlatform).crossType(CrossType.Full).in(file("server"))
  .dependsOn(core)
  .settings(name := "okay-go-game-server", publish / skip := true, libraryDependencies += munit.value)
  .jsSettings(
    libraryDependencies += "io.github.okay-effects" %%% "okay-serverless-cloudflare" % serverless,
    scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.ESModule)),
    worker := {
      val out = (Compile / fullLinkJS / scalaJSLinkerOutputDirectory).value
      val _ = (Compile / fullLinkJS).value
      val to = (ThisBuild / baseDirectory).value / "server" / "worker" / "okay.js"
      IO.copyFile(out / "main.js", to)
      to
    })
  .jvmSettings(
    libraryDependencies += "io.github.okay-effects" %% "okay-serverless-host" % serverless,
    Compile / mainClass := Some("okay.go.server.Serve"),
    run / fork := true, run / baseDirectory := (ThisBuild / baseDirectory).value)

lazy val root = (project in file(".")).aggregate(train, core.jvm, core.js, net.jvm, net.js, app.jvm, app.js, server.jvm,
    server.js)
  .settings(publish / skip := true)
