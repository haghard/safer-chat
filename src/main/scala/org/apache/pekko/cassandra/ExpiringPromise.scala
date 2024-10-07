package org.apache.pekko.cassandra

import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.pattern.after

import scala.concurrent.{ Future, Promise }
import scala.concurrent.duration.FiniteDuration

object ExpiringPromise {
  def apply[T](t: FiniteDuration)(using sys: ActorSystem[?]): Promise[T] = {
    val p = Promise[T]
    val expired = after[T](t)(Future.failed(new Exception("Read timeout")))
    p.completeWith(expired)
    p
  }
}
