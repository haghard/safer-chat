addCommandAlias(
  "chat-a",
  "runMain Main\n" +
    "-Dpekko.remote.artery.canonical.port=2550\n" +
    "-Dpekko.remote.artery.canonical.hostname=127.0.0.1\n" +
    "-Dpekko.management.http.hostname=127.0.0.1\n" +
    "-Dpekko.cluster.multi-data-center.self-data-center=chat-DC"
)


//sudo ifconfig lo0 127.0.0.2 add
addCommandAlias(
  "session-a",
  "runMain Main\n" +
    "-Dpekko.remote.artery.canonical.port=2550\n" +
    "-Dpekko.remote.artery.canonical.hostname=127.0.0.2\n" +
    "-Dpekko.management.http.hostname=127.0.0.2\n" +
    "-Dpekko.cluster.multi-data-center.self-data-center=session-DC"
)

//sudo ifconfig lo0 127.0.0.3 add
addCommandAlias(
  "session-b",
  "runMain Main\n" +
    "-Dpekko.remote.artery.canonical.port=2550\n" +
    "-Dpekko.remote.artery.canonical.hostname=127.0.0.3\n" +
    "-Dpekko.management.http.hostname=127.0.0.3\n" +
    "-Dpekko.cluster.multi-data-center.self-data-center=session-DC"
)

addCommandAlias(
  "bob",
  "runMain client.grpc.main bob"
)

addCommandAlias(
  "alice",
  "runMain client.grpc.main alice",
)

addCommandAlias(
  "default",
  "runMain client.grpc.main default",
)
