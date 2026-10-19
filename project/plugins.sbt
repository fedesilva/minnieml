//resolvers += "simplytyped.com" at "http://simplytyped.com/repo/releases"

addSbtPlugin("com.github.sbt" % "sbt-native-packager" % "1.13.0")

addSbtPlugin("com.eed3si9n" % "sbt-buildinfo" % "0.13.2")


addSbtPlugin("ch.epfl.scala" % "sbt-scalafix" % "0.14.9")

addSbtPlugin("com.timushev.sbt" % "sbt-updates" % "0.7.1")

addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.6.2" )

addSbtPlugin("com.eed3si9n" % "sbt-assembly" % "2.5.0")

// addSbtPlugin("com.sksamuel.scapegoat" %% "sbt-scapegoat" % "1.2.13") // disabled: no Scala 3.8.1 support

addSbtPlugin("org.scoverage" % "sbt-scoverage" % "2.4.4")

addSbtPlugin("com.github.sbt" % "sbt-release" % "1.5.0")
