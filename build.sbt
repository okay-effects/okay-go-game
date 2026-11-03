/** okay-go-game: the game of Go in a page, on okay-ui's screens and okay-faces' Laminar face (specs/go-game.md) */
import sbtcrossproject.CrossPlugin.autoImport.{crossProject, CrossType}

ThisBuild / organization := "io.github.okay-effects"
ThisBuild / version := "0.1.0"
ThisBuild / scalaVersion := "3.3.8"
ThisBuild / scalacOptions ++= Seq("-deprecation", "-feature", "-unchecked", "-Wunused:imports,privates,locals")
ThisBuild / resolvers ++= Seq("okay-ui" at "https://okay-effects.github.io/okay-ui/maven",
  "okay-faces" at "https://okay-effects.github.io/okay-faces/maven",
  "okay-simple" at "https://okay-effects.github.io/okay-simple/maven",
  "okay-scala" at "https://okay-effects.github.io/okay-scala/maven",
  "okay-http" at "https://okay-effects.github.io/okay-http/maven",
  "okay-mcp" at "https://okay-effects.github.io/okay-mcp/maven")

val okayUi = "0.1.0"
val web = taskKey[File]("the page: index.html and its script, linked, in app/.js/target/web")

lazy val app = crossProject(JVMPlatform, JSPlatform).crossType(CrossType.Pure).in(file("app"))
  .settings(name := "okay-go-game", publish / skip := true,
    libraryDependencies ++= Seq("io.github.okay-effects" %%% "okay-ui" % okayUi,
      "org.scalameta" %%% "munit" % "1.2.3" % Test))
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
      streams.value.log.info(s"the page: ${out / "index.html"}")
      out
    })

lazy val root = (project in file(".")).aggregate(app.jvm, app.js).settings(publish / skip := true)
