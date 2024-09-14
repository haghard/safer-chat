package server.grpc

import org.apache.pekko.Done
import org.apache.pekko.actor.CoordinatedShutdown
import org.apache.pekko.actor.CoordinatedShutdown.PhaseBeforeServiceUnbind
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.http.scaladsl.model.{ ContentTypes, HttpEntity, HttpResponse }
import org.apache.pekko.http.scaladsl.server.{ Directives, Route }
import org.apache.pekko.stream.KillSwitches
import org.apache.pekko.stream.scaladsl.{ BroadcastHub, Keep, Sink, Source }
import org.apache.pekko.util.ByteString
import server.grpc.streammon.ClusteredJvmMetrics

import scala.concurrent.Future

final class RestApi(using sys: ActorSystem[?]) extends Directives {

  val bs = 1 << 4
  val ((queue, ks), src) =
    Source
      .queue[ByteString](bs)
      .viaMat(KillSwitches.single)(Keep.both)
      .toMat(BroadcastHub.sink[ByteString](1))(Keep.both)
      .run()

  // to drain metrics if no active clients
  src.runWith(Sink.ignore)

  val metrics = sys.systemActorOf(ClusteredJvmMetrics(queue), "jvm")

  def jvm: Route =
    path("jvm") {
      get {
        complete(
          HttpResponse(entity =
            HttpEntity
              .Chunked
              .fromData(
                ContentTypes.`text/plain(UTF-8)`,
                ClusteredJvmMetrics.jvmMetricsSrc(src, System.currentTimeMillis()),
              )
          )
        )
      }
    }

  CoordinatedShutdown(sys)
    .addTask(PhaseBeforeServiceUnbind, "before-unbind") { () =>
      Future.successful {
        sys.log.info(s"★ ★ ★ before-unbind [shutdown.clusteredJvmMetrics]  ★ ★ ★")
        ks.shutdown()
        Done
      }
    }
}
