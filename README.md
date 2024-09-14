## End-to-end encrypted chat (Pekko + GRPC + scala3)


### Technical details
https://github.com/wiringbits/safer.chat


### GRPC chat

https://youtu.be/a-sBfyiXysI?list=LL
https://carlmastrangelo.com/blog/why-does-grpc-insist-on-trailers
https://github.com/wiringbits/safer.chat


```
While joining a room, the app generates an RSA 2048 key-pair and shares the public key with the server (see Public-key cryptography).
When a participant joins a room, it gets the participants and their public keys from the server.
Each time a message is sent, it is encrypted using each participant's public key and sent to the server, which knows how to reach the participants.
```


Features:

StreamRefs (any flow-controlled message passing between systems) point-to-point streamig,

OTP,

```
Stream Refs.
Delivery guarantees
Stream refs utilise normal actor messaging for their transport, and therefore provide the same level of basic delivery guarantees. Stream refs do extend the semantics somewhat, through demand re-delivery and sequence fault detection. In other words:

- messages are sent over actor remoting
	- which relies on TCP (classic remoting or Artery TCP) or Aeron UDP for basic redelivery mechanisms
- messages are guaranteed to to be in-order
- messages can be lost, however:
	- a dropped demand signal will be re-delivered automatically (similar to system messages)
	- a dropped element signal will cause the stream to fail

Transparent failover

```

```OTP```

https://medium.com/rahasak/public-key-cryptography-with-openssl-4909ea423e67
https://medium.com/rahasak/decode-android-ios-apps-generated-public-keys-in-scala-jvm-and-golang-d9bb3d41b40c
https://blog.bytebytego.com/p/diagram-as-code

```

grpcurl -plaintext 127.0.0.1:8080 list
 grpc.reflection.v1alpha.ServerReflection
 server.grpc.ChatRoom
 server.grpc.Admin
 
```


```

grpcurl -d '{"chat": "aaa", "op":"CREATE"}' -plaintext 127.0.0.1:8080 server.grpc.admin.Admin/AddChat
grpcurl -d '{"chat": "aaa", "op":"ADD", "user":"BpB1m-4nZG6rMyPRVO8m7AbPvpjU7YUZIFy4ab1ve4M"}' -plaintext 127.0.0.1:8080 server.grpc.admin.Admin/AddUser
grpcurl -d '{"chat": "aaa", "op":"ADD", "user":"f1wzkLLuzOW3i6lZX7bAIiPZQOkRTRvFx8aCilAGsMA"}' -plaintext 127.0.0.1:8080 server.grpc.admin.Admin/AddUser

```


```
docker-compose up
docker-compose -f docker-compose5.yml up 
docker ps  
docker exec -it 13a8dddf3b0c cqlsh
```

```

docker exec -it b1e118151e10  cqlsh

CREATE KEYSPACE IF NOT EXISTS chat WITH replication = {'class': 'SimpleStrategy', 'replication_factor': 3 };

SELECT participants, revision FROM chat.chat_details WHERE chat='aaa';
select chat, when from chat.timeline where chat='aaa' and time_bucket='2024-05';
select when from timeline where chat='aaa' and time_bucket='2024-09';
```


TODO:
Error from server: code=2200 [Invalid query] message="Unable to use given strategy class: LocalStrategy is reserved for internal use."
CREATE KEYSPACE IF NOT EXISTS ch WITH replication = {'class': 'LocalStrategy' };


### K8s
https://pekko.apache.org/docs/pekko-samples/current/pekko-sample-grpc-kubernetes-scala/


### Cassandra

https://docs.datastax.com/en/developer/java-driver/4.15/manual/core/
https://www.doanduyhai.com/blog/?p=1859
https://github.com/doanduyhai/killrchat/blob/master/src/main/resources/cassandra/schema_creation.cql
https://github.com/kbr-/scylla-example-app/blob/main/src/main/java/app/Main.java
https://www.sestevez.com/sestevez/CassandraDataModeler/
https://github.com/kbr-/scylla-example-app/blob/main/src/main/java/app/Main.java
https://github.com/apache/cassandra-java-driver/blob/4.x/examples/src/main/java/com/datastax/oss/driver/examples/datatypes/Blobs.java#L61

https://github.com/apache/cassandra-java-driver/blob/4.x/examples/src/main/java/com/datastax/oss/driver/examples/concurrent/LimitConcurrencyRequestThrottler.java

https://github.com/kbr-/scylla-example-app/blob/main/src/main/java/app/Main.java

https://github.com/haghard/akka-pq/blob/master/src/main/scala/sample/blog/processes/StatefulProcess.scala#L149

https://github.com/haghard/linguistic/blob/1b6bc8af7674982537cf574d3929cea203a2b6fa/server/src/main/scala/linguistic/dao/Accounts.scala#L81


https://docs.datastax.com/en/developer/java-driver/4.17/manual/core/
https://docs.datastax.com/en/developer/java-driver/4.17/manual/core/pooling/
https://docs.datastax.com/en/developer/java-driver/4.17/manual/core/configuration/
https://github.com/kbr-/scylla-example-app/blob/main/src/main/java/app/Main.java

https://www.tudorzgureanu.com/define-topic-schema-for-kafka-using-protobuf-with-examples-in-scala/
https://scalapb.github.io/docs/sealed-oneofs/


```

docker exec -it b1e118151e10  cqlsh

```


### Paxos

https://github.com/tlaplus/DrTLAPlus/blob/master/Paxos/Paxos.tla
https://github.com/tlaplus/Examples/blob/master/specifications/Paxos/Paxos.tla

https://www.youtube.com/watch?v=tqU92TI3WJs

https://martinfowler.com/articles/patterns-of-distributed-systems/paxos.html
https://www.mydistributed.systems/2021/04/paxos.html

https://emptysqua.re/blog/review-paxos-quorum-leases/#should-mongodb-implement-this



https://blog.softwaremill.com/painlessly-passing-message-context-through-akka-streams-1615b11efc2c
Full example: streaming from Cassandra event journal


```
final case class MsgMetadata(offset: query.Offset, persistenceId: String, seqNum: Long)

trait EventsJournalOffsetDao {
  def offsetFor(projection: ProjectionId): Future[Option[query.Offset]]
  def saveOffset(projection: ProjectionId, currentOffset: query.Offset): Future[Unit]
}

class EventsStreamFactory(
    config: EventsStreamConfig,
    val projectionId: ProjectionId,
    projectionFlow: FlowWithContext[AppEvent, MsgMetadata, AppEvent, MsgMetadata, NotUsed],
    journalOffsetDao: EventsJournalOffsetDao
) {

def createStream(eventJournal: CassandraReadJournal): Source[Unit, NotUsed] = {
  RestartSource
    .withBackoff(minBackoff = 200.millis, maxBackoff = 3.seconds, randomFactor = 0.2) { () =>
      SourceWithContext
        .fromTuples(
          Source
            .fromFuture(journalOffsetDao.offsetFor(projectionId))
            .map(_.getOrElse(query.Offset.timeBasedUUID(eventJournal.firstOffset)))
            .flatMapConcat { currentOffset =>
              eventJournal
                .eventsByTag("MyTag", currentOffset)
            }
            .collect {
              case EventEnvelope(offset, persistenceId, sequenceNr, event: AppEvent) =>
                (event, MsgMetadata(offset, persistenceId, sequenceNr))
            }
        )
        .via(projectionFlow)
        .asSource
        .mapAsync(1) {
          case (_, context) => journalOffsetDao.saveOffset(projectionId, context.offset)
        }
    }
}
```


TODO: Try this

```
remote.origin.url=https://github.com/improving-ottawa/akka-durable-state-demo.git

def changesBySlices(
    sliceRange: Range
  )(implicit
    system: ActorSystem[_]
  ): Projection[DurableStateChange[DeliveryDateEntity.DeliveryDateState]] = {
    val topic = system
      .settings
      .config
      .getString("delivery-date-service.projections.topic")

    val sourceProvider =
      DurableStateSourceProvider
        .changesBySlices[DeliveryDateEntity.DeliveryDateState](
          system = system,
          durableStateStoreQueryPluginId = R2dbcDurableStateStore.Identifier,
          entityType = DeliveryDateEntity.EntityName,
          minSlice = sliceRange.min,
          maxSlice = sliceRange.max
        )

    val projectionKey = s"change-${sliceRange.min}-${sliceRange.max}"
    R2dbcProjection.atLeastOnceAsync(
      projectionId = ProjectionId("EgressProjection", projectionKey),
      settings = None,
      sourceProvider = sourceProvider,
      handler = () =>
        new EgressProjectionHandler(
          system = system,
          topic = topic,
          sendProducer = sendProducer(projectionKey)
        )
    )
  }
  
```



```

-1) Range-based hashing for chats 

0) Local cassandra


5)
object Reverse:
  opaque type Reverse[A] = A
  def apply[A](a: A): Reverse[A] = a
  given [A](using A: Ordering[A]): Ordering[Reverse[A]] with
    def compare(x: Reverse[A], y: Reverse[A]) = A.compare(y, x)
end Reverse

List(5, 3, 1, 7, 9, 2).sortBy(Reverse(_))


6) Jaegertracing
image: jaegertracing/all-in-one:1.55

https://github.com/adamw/direct-style-pres.git
https://softwaremill.com/observability-part-2-build-a-local-try-me-environment/

https://tudorzgureanu.com/define-topic-schema-for-kafka-using-protobuf-with-examples-in-scala/


7)
table with all chatNames
on createChat


8) new Api to stream messages by chatName 
BloomFilter for all chats


9) Chat == Game == Any Conversation app

10) https://github.com/phiSgr/gatling-grpc/blob/master/src/test/scala/com/github/phisgr/example/GrpcExample.scala


Sec
https://github.com/propensive/gastronomy


https://github.com/vincenzobaz/spark-scala3/blob/main/build.sbt
semanticdbEnabled


https://scalac.io/blog/kalix-tutorial-building-invoice-application/
--------------------------------------------------------------------
https://softwaremill.com/schema-evolution-protobuf-scalapb-fs2grpc/
--------------------------------------------------------------------


ChatRoom as a separate entity
Milti-dc

GRPC
https://enlear.academy/connecting-to-secured-grpc-server-using-grpcurl-d4884a06d04f

ChatRoom -> ChatRoomSession

```


https://softwaremill.com/sse-vs-websockets-comparing-real-time-communication-protocols/
web-socket
tcp


https://enlear.academy/connecting-to-secured-grpc-server-using-grpcurl-d4884a06d04f

==========================================
I created a #chatgpt #Scala Cats Effect Tutor for fun take a look all:
chat.openai.com/g/g-anUfWL8Ty-…
Feedback to make it better appreciated. I will be iterating on it, this is just an initial attempt.
Have fun all!
=====================================

https://enlear.academy/connecting-to-secured-grpc-server-using-grpcurl-d4884a06d04f

graalvm


Life should have taught me by now to not promise anything but screw it. 1-2 weeks.

https://github.com/apache/pekko-persistence-cassandra/blob/main/docker-compose.yml



https://github.com/lbialy/1brc/blob/scala-and-scala-native/scala/CalculateAverage_lbialy.scala
https://github.com/ddd-by-examples/library?s=03



-- the-real-reason-to-use-protobuf
https://buf.build/blog/the-real-reason-to-use-protobuf


https://github.com/nolangrace/akka-playground/blob/master/src/main/scala/com/example/AkkaMergeHubExample.scala

https://www.linkedin.com/pulse/migrating-event-sourced-akka-application-from-cockroachdb-alloydb-wqgvf%3FtrackingId=FXFfb9AYTp%252B5LjLzsij%252BpQ%253D%253D/?trackingId=FXFfb9AYTp%2B5LjLzsij%2BpQ%3D%3D



https://emptysqua.re/blog/review-distributed-transactions-at-scale-dynamodb/
https://www.infoq.com/articles/amazon-dynamodb-transactions/
http://muratbuffalo.blogspot.com/2023/08/distributed-transactions-at-scale-in.html?m=1

http://muratbuffalo.blogspot.com/2023/10/hints-for-distributed-systems-design.html


https://netflixtechblog.com/scaling-event-sourcing-for-netflix-downloads-episode-2-ce1b54d46eec

https://idiomaticsoft.com/post/2023-08-08-qdsl/


Dynomite supports multi-datacenter replication and is designed for high availability.
https://netflixtechblog.com/introducing-dynomite-making-non-distributed-databases-distributed-c7bce3d89404



https://github.com/nMoncho/helenus/blob/main/build.sbt



https://www.innoq.com/en/blog/2023/11/schema-evolution-avro/

https://github.com/apache/pekko-samples/blob/main/pekko-sample-persistence-dc-scala/src/main/scala/sample/persistence/res/auction/Auction.scala

https://github.com/risingwavelabs/risingwave/blob/main/proto/expr.proto


https://questdb.io/blog/leveraging-rust-in-our-high-performance-java-database/

https://www.jamesmichaelhickey.com/event-sourcing-eventual-consistency-isnt-necessary/

----------------------------
https://github.com/ruippeixotog/akka-stream-mon.git
https://github.com/ruippeixotog/akka-stream-mon/blob/master/src/main/scala/net/ruippeixotog/streammon/ThroughputMonitor.scala


ed25519 keypair
https://youtu.be/uj-7Y_7p4Dg?t=663

slick


End-to-end encrypted chat app with  (Pekko + GRPC + scala3)

https://softwaremill.com/websocket-chat-using-structured-concurrency-ox-and-tapir/
https://blog.rockthejvm.com/scala-redis-websockets-part-2/#61-registering-users


Encript some data on your behalf

The public key allows anyone to encript data for you.
The priv kay allows you to descript that data for you but only the priv key holder can descript.



Big IDEA: Privacy preserving apps
https://youtu.be/31lM3vptSEg?list=LL


https://youtu.be/31lM3vptSEg?list=LL


Users that are allowed to post to a particualr chat-room.


https://github.com/tarao/lambda-scala3.git
https://github.com/tarao/record4s.git



https://antonz.org/uuidv7/
https://github.com/guizmaii-opensource/zio-uuid/blob/main/zio-uuid/src/main/scala/zio/uuid/internals/UUIDBuilder.scala


https://choosealicense.com/


https://github.com/rockset/rocksdb-cloud/blob/master/util/bloom_impl.h?s=03#L94-L142

UUIDv7

New api

All users

DM

Bloom-Filter
https://github.com/rockset/rocksdb-cloud/blob/master/util/bloom_impl.h?s=03#L94-L142
https://github.com/apache/pekko-samples/blob/main/pekko-sample-cluster-client-grpc-scala/src/main/scala/sample/cluster/client/grpc/ClusterClient.scala


End-to-end encryption demystified: Nik Graf (Local-first Conf)
https://youtu.be/uJLr8L-D9LE

cc @ScalaTimes



Coupling a unit of transactionallity and data consistency to the unit of data (entity) that can be reacted upon.
Atomic read-modify-write loop.


https://reqbin.com/req/c-lfozgltr/curl-https-request
curl --no-buffer -k https://127.0.0.1:8443/jvm
curl --cacert ./src/main/resources/fsa/fullchain.pem https://127.0.0.1:8443/jvm


TODO:
1.Add bloomfilter for chatrooms

2.
akka-grpc-scala-js-grpcweb https://github.com/ptrdom/akka-grpc-scala-js-grpcweb/blob/master/client/src/main/scala/com/example/client/Client.scala
Chat WEB-ui
https://blog.rockthejvm.com/scala-redis-websockets-part-2/#11-serving-html
http://localhost:8080/chat.html

3.Jaegertracing  image: jaegertracing/all-in-one:1.55
https://github.com/adamw/direct-style-pres.git
and
OpenTelemetry With Scala Futures
https://github.com/wsargent/opentelemetry-with-scala-futures



docker exec -it 7f5692cb35cd cqlsh -u cassandra -p cassandra
CREATE user schat WITH password 'sdfj345_34uyasdjgkdjgeur37uHHFS0Q0c-l+a';
docker-compose -f docker-compose5.yml up

jcmd <PID> GC.heap_dump <file>
jcmd <PID> Thread.print