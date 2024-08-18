addCommandAlias(
  "a",
  "runMain Main\n" +
    "-Dpekko.remote.artery.canonical.port=2550\n" +
    "-Dpekko.remote.artery.canonical.hostname=127.0.0.1\n" +
    "-Dpekko.management.http.hostname=127.0.0.1\n" +
    "-DSSL_KEY_STORE_PASSWORD=123456\n" +
    "-DSSL_KEY_PASSWORD=123456\n" +
    "-DSSL_TRUST_STORE_PASSWORD=123456"
)

//sudo ifconfig lo0 127.0.0.2 add
addCommandAlias(
  "b",
  "runMain Main\n" +
    "-Dpekko.remote.artery.canonical.port=2550\n" +
    "-Dpekko.remote.artery.canonical.hostname=127.0.0.2\n" +
    "-Dpekko.management.http.hostname=127.0.0.2\n" +
    "-DSSL_KEY_STORE_PASSWORD=123456\n" +
    "-DSSL_KEY_PASSWORD=123456\n" +
    "-DSSL_TRUST_STORE_PASSWORD=123456"
)

addCommandAlias(
  "bob",
  "runMain client.grpc.main bob" +
    "-DSSL_KEY_STORE_PASSWORD=123456\n" +
  "-DSSL_KEY_PASSWORD=123456\n" +
  "-DSSL_TRUST_STORE_PASSWORD=123456"
)

addCommandAlias(
  "alice",
  "runMain client.grpc.main alice",
)

addCommandAlias(
  "default",
  "runMain client.grpc.main default",
)
