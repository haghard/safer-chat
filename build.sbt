val scala3Version = "3.4.1"
val pekkoV = "1.0.2"
val pekkoHttpV = "1.0.0"
val ProjectName = "safer-chat"

resolvers += "Apache Snapshots" at "https://repository.apache.org/content/repositories/snapshots/"

//show scalacOptions
lazy val scalac3Settings = Seq(
  scalacOptions ++= Seq(
    "-deprecation",
    "-feature",
    "-language:implicitConversions",
    "-unchecked",
    "-Ykind-projector",
    "-Ysafe-init", // guards against forward access reference
    "-language:adhocExtensions",
    "-release:17",
    //https://github.com/apache/pekko-grpc/blob/88e8567e2decbca19642e5454729aa78cce455eb/project/Common.scala#L72
    "-Wconf:msg=Marked as deprecated in proto file:silent",
    "-Wconf:msg=pattern selector should be an instance of Matchable:silent",
    "-Xfatal-warnings",

    // "-Ytasty-reader",
    "-Wunused:imports",
    "-no-indent", // forces to use braces
  ) ++ Seq("-rewrite" /*, "-indent"*/ ) ++ Seq("-source", "future-migration")
)

lazy val root = project
  .in(file("."))
  .settings(scalac3Settings)
  .settings(
    name := ProjectName,
    organization := "haghard",
    version := "0.0.1-SNAPSHOT",
    scalaVersion := scala3Version,
    startYear := Some(2024),
    developers := List(Developer("haghard", "Vadim Bondarev", "hagard84@gmail.com", url("https://github.com/haghard"))),

    // sbt headerCreate
    licenses += ("Apache-2.0", new URL("https://www.apache.org/licenses/LICENSE-2.0.txt")),
    headerMappings := headerMappings.value + (HeaderFileType.scala -> HeaderCommentStyle.cppStyleLineComment),
    headerLicense := Some(
      HeaderLicense.Custom(
        """|Copyright (c) 2024 by Vadim Bondarev
           |This software is licensed under the Apache License, Version 2.0.
           |You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0.
           |""".stripMargin
      )
    ),
    libraryDependencies ++= Seq(
      /*
        show dependencyList
        org.apache.pekko:pekko-actor_3:1.0.2
        org.apache.pekko:pekko-discovery_3:1.0.2
      */
      "org.apache.pekko" %% "pekko-protobuf-v3" % pekkoV,
      "org.apache.pekko" %% "pekko-actor-typed" % pekkoV,
      "org.apache.pekko" %% "pekko-cluster-sharding-typed" % pekkoV,
      "org.apache.pekko" %% "pekko-distributed-data" % pekkoV,
      "org.apache.pekko" %% "pekko-persistence-typed" % pekkoV,
      "org.apache.pekko" %% "pekko-stream-typed" % pekkoV,

      "org.apache.pekko" %% "pekko-management" % "1.0.0",
      "org.apache.pekko" %% "pekko-management-cluster-bootstrap" % "1.0.0",

      "ch.qos.logback" % "logback-classic" % "1.2.11", //"1.4.14"

      "org.apache.pekko" %% "pekko-slf4j" % pekkoV,

      "com.madgag.spongycastle" % "core" % "1.58.0.0",
      "org.bouncycastle" % "bcpkix-jdk18on" % "1.78.1",

      "io.aeron" % "aeron-driver" % "1.44.1",
      "io.aeron" % "aeron-client" % "1.44.1",

      "org.wvlet.airframe" %% "airframe-ulid" % "24.5.0",
      "com.github.bastiaanjansen" % "otp-java" % "2.0.3",
      "com.datastax.oss" % "java-driver-core" % "4.17.0",
    ),

    // dependencyOverrides ++= Seq(),
    assemblyMergeStrategy := {
      case PathList("META-INF", xs @ _*)                                  => MergeStrategy.discard
      case PathList(xs @ _*) if xs.last == "module-info.class"            => MergeStrategy.discard
      case PathList(xs @ _*) if xs.last == "io.netty.versions.properties" => MergeStrategy.rename
      case "application.conf"                                             => MergeStrategy.concat
      case "version.conf"                                                 => MergeStrategy.concat
      case "reference.conf"                                               => MergeStrategy.concat
      case other                                                          =>
        // (assembly / assemblyMergeStrategy).value(other)
        MergeStrategy.first
    },
    mainClass := Some("Main"),
    assemblyJarName := s"$ProjectName-${version.value}.jar",
    // java.base/sun.nio.ch=ALL-UNNAMED
    // assemblyAppendContentHash := true,

    dockerBaseImage := "haghard/jdk17-open-table:1.0.1",
    dockerRepository := Some("haghard"),
    dockerExposedPorts := Seq(8080, 8558, 25520),
    Docker / daemonUser := "root",
    Docker / mainClass := Some("Main"),
    Docker / daemonUserUid := None,
    buildInfoPackage := "server.grpc",
    buildInfoKeys := Seq[BuildInfoKey](
      version,
      scalaVersion,
      sbtVersion,
      "gitHash" -> SbtUtils.fullGitHash.getOrElse(""),
      "gitBranch" -> SbtUtils.branch.getOrElse(""),
    ),
    dynverSeparator := "-",
    // scalaBinaryVersion := "3", //"2.13"

    // make version compatible with docker for publishing
    ThisBuild / dynverSeparator := "-",
    javaOptions ++= Seq(
      "-XX:+PrintFlagsFinal",
      "-XX:+PrintCommandLineFlags",
      //"-XX:+PrintGCDetails",
      //"-XshowSettings:vm",

      "-Xms212m",
      "-Xmx256m",
      "-XshowSettings:system -version",

      "-XX:ThreadStackSize=1048576", //[0 ... 1048576]
      "-XX:ReservedCodeCacheSize=251658240",
      "-XX:MaxDirectMemorySize=128m",

      /*"-XX:+PrintFieldLayout",*/
      /*"-XX:MaxMetaspaceSize=650m",*/
      /*"-XX:+UseG1GC"*/

      // https://www.baeldung.com/java-flight-recorder-monitoring
      // "-XX:+FlightRecorder",
      // "-XX:StartFlightRecording=duration=500s,filename=./flight.jfr",

      //https://softwaremill.com/reactive-event-sourcing-benchmarks-part-2-postgresql/
      //"-XX:ActiveProcessorCount=4",

      // https://dzone.com/articles/troubleshooting-problems-with-native-off-heap-memo
      // To allow getting native memory stats for threads
      "-XX:NativeMemoryTracking=summary", // detail

      // "-XX:MetaspaceSize=20M",
      // https://youtu.be/kKigibHrV5I
      // "-XX:-UseAdaptiveSizePolicy", // -UseAdaptiveSizePolicy --disable use
      "-XX:+UseZGC", // https://www.baeldung.com/jvm-zgc-garbage-collector
      "--add-opens",
      "java.base/sun.nio.ch=ALL-UNNAMED",

      //https://youtu.be/vh4qAsxegNY?list=LL

      //https://github.com/docker-library/docs/blob/2bb63e73456f4bc836c5e42d6871131a82e548f1/openjdk/content.md?plain=1#L56
      //RAM limit is supported by Windows Server containers, but currently the JVM cannot detect it.
      // To prevent excessive memory allocations, `-XX:MaxRAM=...` option must be specified with the value that is not bigger than the containers RAM limit.
      //"-XX:MaxRAM=412m",

      //TODO: Check this out: https://github.com/kamilkloch/websocket-benchmark/blob/master/build.sbt

      /*
      "--add-exports=java.base/sun.nio.ch=ALL-UNNAMED",
      "--add-opens=java.base/java.lang=ALL-UNNAMED",
      "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED",
      "--add-opens=java.base/java.io=ALL-UNNAMED",
      "--add-exports=jdk.unsupported/sun.misc=ALL-UNNAMED",
      */
    ),

    // comment out for test:run
    run / fork := true,
    run / connectInput := true,
    //run / javaOptions ++= unnamedJavaOptions
  )
  .enablePlugins(PekkoGrpcPlugin, JavaAppPackaging, BuildInfoPlugin)

shellPrompt := { state => s"${SbtUtils.prompt(ProjectName)}> " }

scalafmtOnCompile := true

addCommandAlias("c", "scalafmt;compile")
addCommandAlias("r", "reload")
addCommandAlias("as", "clean;assembly")

// See https://github.com/apache/spark/blob/v3.3.2/launcher/src/main/java/org/apache/spark/launcher/JavaModuleOptions.java
val unnamedJavaOptions = List(
  "-XX:+IgnoreUnrecognizedVMOptions",
  "--add-opens=java.base/java.lang=ALL-UNNAMED",
  "--add-opens=java.base/java.lang.invoke=ALL-UNNAMED",
  "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED",
  "--add-opens=java.base/java.io=ALL-UNNAMED",
  "--add-opens=java.base/java.net=ALL-UNNAMED",
  "--add-opens=java.base/java.nio=ALL-UNNAMED",
  "--add-opens=java.base/java.util=ALL-UNNAMED",
  "--add-opens=java.base/java.util.concurrent=ALL-UNNAMED",
  "--add-opens=java.base/java.util.concurrent.atomic=ALL-UNNAMED",
  "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
  "--add-opens=java.base/sun.nio.cs=ALL-UNNAMED",
  "--add-opens=java.base/sun.security.action=ALL-UNNAMED",
  "--add-opens=java.base/sun.util.calendar=ALL-UNNAMED",
  "--add-opens=java.security.jgss/sun.security.krb5=ALL-UNNAMED"
)

//java --add-opens java.base/sun.nio.ch=ALL-UNNAMED -jar -Dpekko.remote.artery.canonical.hostname=127.0.0.1 -Dpekko.management.http.hostname=127.0.0.1 ./target/scala-3.4.1/safer-chat-0.0.1-SNAPSHOT.jar
//java --add-opens java.base/sun.nio.ch=ALL-UNNAMED -jar -Dpekko.remote.artery.canonical.hostname=127.0.0.2 -Dpekko.management.http.hostname=127.0.0.2 ./target/scala-3.4.1/safer-chat-0.0.1-SNAPSHOT.jar

//show dependencyList
