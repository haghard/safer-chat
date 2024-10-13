val scala3Version = "3.5.1"

//https://pekko.apache.org/docs/pekko/current/release-notes/releases-1.1.html
//https://github.com/apache/pekko/tags
val pekkoV="1.1.2"

val logbackVersion = "1.5.8"
val slf4jVersion   = "2.0.16"

//https://github.com/apache/pekko-http/tags
val pekkoHttpV = "1.1.0"

//https://github.com/apache/pekko-management/tags
val PekkoManagementVersion = "1.0.0"

val ProjectName = "safer-chat"
val AmmoniteVersion = "3.0.0"

val AppVersion = "0.1.1"

resolvers ++= Seq(
  "Apache Snapshots" at "https://repository.apache.org/content/repositories/snapshots/"
)

//show scalacOptions
lazy val scalac3Settings = Seq(
  scalacOptions ++= Seq(
    "-deprecation",
    "-feature",
    "-language:implicitConversions",
    "-unchecked",
    //"-Xkind-projector",
    //"-Wsafe-init",
    "-language:adhocExtensions",

    //https://www.scala-lang.org/blog/2022/04/12/scala-3.1.2-released.html
    //-release is now -java-output-version, and -Xtarget is -Xunchecked-java-output-version.

    //https://github.com/apache/pekko-grpc/blob/88e8567e2decbca19642e5454729aa78cce455eb/project/Common.scala#L72
    "-Wconf:msg=Marked as deprecated in proto file:silent",
    "-Wconf:msg=pattern selector should be an instance of Matchable:silent",
    "-Wconf:msg=is deprecated for wildcard arguments of types:silent",
    "-Wconf:msg=qualifier will be deprecated in the future; it should be dropped:silent",

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
    version := AppVersion,
    scalaVersion := scala3Version,

    semanticdbEnabled := true,
    semanticdbVersion := scalafixSemanticdb.revision,

    usePipelining := true,
    startYear := Some(2024),
    developers := List(Developer("haghard", "Vadim Bondarev", "haghard84@gmail.com", url("https://github.com/haghard"))),

    // sbt headerCreate
    licenses += ("Apache-2.0", new java.net.URL("https://www.apache.org/licenses/LICENSE-2.0.txt")),
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
      //  show dependencyList
      "org.apache.pekko" %% "pekko-http" % pekkoHttpV,
      "org.apache.pekko" %% "pekko-http-spray-json"% pekkoHttpV,

      "org.apache.pekko" %% "pekko-protobuf-v3" % pekkoV,
      "org.apache.pekko" %% "pekko-actor-typed" % pekkoV,
      "org.apache.pekko" %% "pekko-cluster-sharding-typed" % pekkoV,
        //.excludeAll(ExclusionRule(organization = "org.apache.pekko", name = "pekko-protobuf-v3")),
        //.exclude("org.slf4j", "slf4j-api")

      "org.apache.pekko" %% "pekko-distributed-data" % pekkoV,
      "org.apache.pekko" %% "pekko-persistence-typed" % pekkoV,
      "org.apache.pekko" %% "pekko-stream-typed" % pekkoV,

      "org.apache.pekko" %% "pekko-coordination" % pekkoV,
      "org.apache.pekko" %% "pekko-cluster-metrics" % pekkoV,

      "org.apache.pekko" %% "pekko-management" % PekkoManagementVersion,
      "org.apache.pekko" %% "pekko-management-cluster-bootstrap" % PekkoManagementVersion,
      "org.apache.pekko" %% "pekko-management-cluster-http" % PekkoManagementVersion,

      //protobuf-java-3.21.12.jar, jar org = com.google.protobuf, entry target = google/protobuf/struct.proto

      //https://pekko.apache.org/docs/pekko-persistence-r2dbc/current/query.html#publish-events-for-lower-latency-of-eventsbyslices
      //"org.apache.pekko" %% "pekko-persistence-r2dbc" % "1.0.0",

      "org.apache.pekko" %% "pekko-slf4j" % pekkoV,
      "ch.qos.logback" % "logback-classic" %  logbackVersion,
      "org.slf4j"      % "slf4j-api"       %  slf4jVersion,

      "com.madgag.spongycastle" % "core" % "1.58.0.0",
      "org.bouncycastle" % "bcpkix-jdk18on" % "1.78.1",

      //https://tarao.orezdnu.org/record4s/
      //"com.github.tarao" %% "record4s" % "0.13.0",

      "io.aeron" % "aeron-driver" % "1.46.0", //is jdk17 only
      "io.aeron" % "aeron-client" % "1.46.0",

      "org.wvlet.airframe" %% "airframe-ulid" % "24.7.1",
      "com.github.bastiaanjansen" % "otp-java" % "2.0.3",
      "com.datastax.oss" % "java-driver-core" % "4.17.0",

      //https://ratis.apache.org/
      //https://youtu.be/ef9QVG5RSWs?list=LL
      //"org.apache.ratis" % "ratis-server" % "3.1.1" % "provided",

      /*("com.lihaoyi" % "ammonite" % AmmoniteVersion % "test" cross CrossVersion.full)
        .exclude("com.thesamet.scalapb", "lenses_2.13")
        .exclude("com.thesamet.scalapb", "scalapb-runtime_2.13")
        .exclude("org.slf4j", "slf4j-api"),
      */
      
      //https://github.com/scalag/scalag/blob/master/build.sbt
      //https://github.com/dialex/JColor
      //https://github.com/ComputeNode/scalag/blob/master/build.sbt
      //"com.lihaoyi" % "pprint_3" % "0.9.0",
      //"com.diogonunes" % "JColor" % "5.5.1",
    ),

    dependencyOverrides ++= Seq(
      "org.apache.pekko" %% "pekko-discovery" % pekkoV,
      "org.apache.pekko" %% "pekko-protobuf-v3" % pekkoV,
      "org.apache.pekko" %% "pekko-actor-typed" % pekkoV,
      "org.apache.pekko" %% "pekko-cluster-sharding-typed" % pekkoV,
      "org.apache.pekko" %% "pekko-distributed-data" % pekkoV,
      "org.apache.pekko" %% "pekko-persistence-typed" % pekkoV,
      "org.apache.pekko" %% "pekko-stream-typed" % pekkoV,
      "org.apache.pekko" %% "pekko-slf4j" % pekkoV,

      "org.apache.pekko" %% "pekko-coordination" % pekkoV,
      "org.apache.pekko" %% "pekko-management" % PekkoManagementVersion,
      "org.apache.pekko" %% "pekko-management-cluster-bootstrap" % PekkoManagementVersion,
      "org.apache.pekko" %% "pekko-management-cluster-http" % PekkoManagementVersion,
    ),

    /*assemblyMergeStrategy := {
      case PathList("META-INF", "versions", "9", "module-info.class")     => MergeStrategy.discard
      case PathList("module-info.class")                                  => MergeStrategy.discard
      case PathList("META-INF", xs @ _*)                                  => MergeStrategy.discard
      case PathList(xs @ _*) if xs.last == "io.netty.versions.properties" => MergeStrategy.rename
      case "application.conf"                                             => MergeStrategy.concat
      case "version.conf"                                                 => MergeStrategy.concat
      case "reference.conf"                                               => MergeStrategy.concat
      // https://github.com/akka/akka/issues/29456
      case PathList("google", "protobuf", _)    => MergeStrategy.discard
      case PathList("google", "protobuf", _, _) => MergeStrategy.discard
      case other                                                          =>
        (assembly / assemblyMergeStrategy).value(other)
    },*/

    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", _*) => MergeStrategy.discard
      case PathList(xs @ _*) if xs.last == "io.netty.versions.properties" => MergeStrategy.rename
      case PathList("module-info.class") => MergeStrategy.discard
      /*
      https://github.com/akka/akka/issues/29456
      case PathList("google", "protobuf", _)    => MergeStrategy.discard
      case PathList("google", "protobuf", _, _) => MergeStrategy.discard
      */
      case x =>
        val oldStrategy = (assembly / assemblyMergeStrategy).value
        oldStrategy(x)
    },

    mainClass := Some("chatServer"),
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
    scalaBinaryVersion := "3", //"2.13"

    // make version compatible with docker for publishing
    ThisBuild / dynverSeparator := "-",
    javaOptions ++= Seq(
      //"-XX:+PrintFlagsFinal",
      "-XX:+PrintCommandLineFlags",

      //"-XshowSettings:system -version",
      "-XshowSettings:all",
      
      //"-XX:+PrintGCDetails",
      //"-XshowSettings:vm",

      "-Xms256m",
      "-Xmx256m",
      "-XX:+AlwaysPreTouch", //

      //"-XX:ThreadStackSize=1048576", //[0 ... 1048576]
      //"-XX:ReservedCodeCacheSize=251658240",
      "-XX:MaxDirectMemorySize=64m",

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

      "--add-opens", "java.base/sun.nio.ch=ALL-UNNAMED",

      //"--add-opens", "java.base/java.nio=ALL-UNNAMED",
      
      //https://youtu.be/vh4qAsxegNY?list=LL

      //https://github.com/docker-library/docs/blob/2bb63e73456f4bc836c5e42d6871131a82e548f1/openjdk/content.md?plain=1#L56
      //RAM limit is supported by Windows Server containers, but currently the JVM cannot detect it.
      // To prevent excessive memory allocations, `-XX:MaxRAM=...` option must be specified with the value that is not bigger than the containers RAM limit.
      //"-XX:MaxRAM=412m",

      //https://github.com/kamilkloch/websocket-benchmark/blob/master/build.sbt

      /*
      "--add-exports=java.base/sun.nio.ch=ALL-UNNAMED",
      "--add-opens=java.base/java.lang=ALL-UNNAMED",
      "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED",
      "--add-opens=java.base/java.io=ALL-UNNAMED",
      "--add-exports=jdk.unsupported/sun.misc=ALL-UNNAMED",
      */
    ),

    javaHome := Some(file("/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home/")),
    //javaHome := Some(file("/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home/")),
    //javaHome := Some(file("/Library/Java/JavaVirtualMachines/jdk-23.jdk/Contents/Home/")),
    
    //comment out to run ammonite (test:run)
    run / fork := true,
    run / connectInput := true,
  )
  .enablePlugins(PekkoGrpcPlugin, JavaAppPackaging, BuildInfoPlugin)

shellPrompt := { state => s"${SbtUtils.prompt(ProjectName)}> " }

scalafmtOnCompile := true

Test / sourceGenerators += Def.task {
  val file = (Test / sourceManaged).value / "amm.scala"
  IO.write(file, """object amm extends App { ammonite.Main().run() }""")
  Seq(file)
}.taskValue

addCommandAlias("c", "scalafmt;compile")
addCommandAlias("r", "reload")
addCommandAlias("ca", "clean;assembly")

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

//java --add-opens java.base/sun.nio.ch=ALL-UNNAMED -Dpekko.remote.artery.canonical.hostname=127.0.0.1 -Dpekko.management.http.hostname=127.0.0.1 -Dpekko.cluster.multi-data-center.self-data-center=chat-DC -jar ./target/scala-3.3.4/safer-chat-0.1.1.jar

//java --add-opens java.base/sun.nio.ch=ALL-UNNAMED -jar -Dpekko.remote.artery.canonical.hostname=127.0.0.1 -Dpekko.management.http.hostname=127.0.0.1 -Dpekko.cluster.multi-data-center.self-data-center=chat-DC ./target/scala-3.5.1/safer-chat-0.1.1.jar
//java --add-opens java.base/sun.nio.ch=ALL-UNNAMED -jar -Dpekko.remote.artery.canonical.hostname=127.0.0.2 -Dpekko.management.http.hostname=127.0.0.2 ./target/scala-3.5.1/safer-chat-0.1.0.jar
//show dependencyList


/*
-Dslf4j.provider=ch.qos.logback.classic.spi.LogbackServiceProvider
java --add-opens java.base/sun.nio.ch=ALL-UNNAMED -Dpekko.remote.artery.canonical.hostname=127.0.0.1 -Dpekko.management.http.hostname=127.0.0.1 -Dpekko.cluster.multi-data-center.self-data-center=chat-DC -jar ./target/scala-3.5.1/safer-chat-0.1.1.jar
*/