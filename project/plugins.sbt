// scalastyle:off
logLevel := Level.Warn

resolvers ++= DefaultOptions.resolvers(snapshot = false)
resolvers ++= Resolver.sonatypeOssRepos("releases")
resolvers ++= Seq(
  Resolver.typesafeRepo("releases"),
  // Remove below resolver once the following issues has been resolved:
  // https://issues.jboss.org/projects/JBINTER/issues/JBINTER-21
  "JBoss" at "https://repository.jboss.org/"
)

// sbt-git plugin to get access to git commands from the build scripts
addSbtPlugin("com.github.sbt" % "sbt-git" % "2.0.0")

// Dependency handling
addSbtPlugin("com.timushev.sbt" % "sbt-updates" % "0.6.3")

// Formatting and style checking
addSbtPlugin("org.scalameta"       % "sbt-scalafmt"   % "2.4.6")
addSbtPlugin("com.beautiful-scala" % "sbt-scalastyle" % "1.5.1")

// Code coverage
addSbtPlugin("org.scoverage" %% "sbt-scoverage" % "1.9.3")

// Native packaging plugin
addSbtPlugin("com.github.sbt" %% "sbt-native-packager" % "1.9.9")

// Release plugin
addSbtPlugin("com.github.sbt" % "sbt-release" % "1.1.0")

// Gatling plugin
//addSbtPlugin("io.gatling" % "gatling-sbt" % "3.1.0")

//classpathTypes += "maven-plugin"

// Scalafix for automated code re-writes when updating dependencies
addSbtPlugin("ch.epfl.scala" % "sbt-scalafix" % "0.10.1")

// documentation generator
addSbtPlugin("org.scalameta" % "sbt-mdoc" % "2.3.3")
