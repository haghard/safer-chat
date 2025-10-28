//https://github.com/apache/pekko-grpc/tags
addSbtPlugin("org.apache.pekko" % "pekko-grpc-sbt-plugin" % "1.2.0")

addSbtPlugin("com.eed3si9n" % "sbt-assembly" % "2.3.0")

addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.5.5")

addSbtPlugin("com.github.sbt" % "sbt-native-packager" % "1.11.0")
addSbtPlugin("de.heikoseeberger" % "sbt-header" % "5.10.0")

addSbtPlugin("com.github.sbt" % "sbt-dynver" % "5.1.0")
addSbtPlugin("com.timushev.sbt" % "sbt-rewarn" % "0.1.3")
addSbtPlugin("com.eed3si9n" % "sbt-buildinfo" % "0.13.0")

addSbtPlugin("ch.epfl.scala" % "sbt-scalafix" % "0.14.3")

addDependencyTreePlugin
