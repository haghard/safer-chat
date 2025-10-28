package org.apache.pekko.cluster.sharding

import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.ActorRef
import org.apache.pekko.actor.typed.scaladsl.adapter.TypedActorSystemOps
import org.apache.pekko.cluster.sharding.ShardCoordinator.ShardAllocationStrategy
import org.apache.pekko.cluster.sharding.ShardRegion.ShardId
import org.apache.pekko.cluster.typed.Cluster

import scala.concurrent.Future

final class LeastShardAllocationStrategyWithLogger(
    absoluteLimit: Int,
    relativeLimit: Double,
  )(using
    cluster: Cluster,
    system: ActorSystem[?])
    extends ShardAllocationStrategy {
  val delegate =
    org
      .apache
      .pekko
      .cluster
      .sharding
      .ShardCoordinator
      .ShardAllocationStrategy
      .leastShardAllocationStrategy(absoluteLimit, relativeLimit)
      .asInstanceOf[org.apache.pekko.cluster.sharding.internal.LeastShardAllocationStrategy]

  delegate.start(system.toClassic)

  /** Invoked periodically to decide which shards to rebalance to another location.
    *
    * @param currentShardAllocations
    *   all actor refs to `ShardRegion` and their current allocated shards, in the order they were allocated
    * @param rebalanceInProgress
    *   set of shards that are currently being rebalanced, i.e. you should not include these in the returned set
    * @return
    *   a `Future` of the shards to be migrated, may be empty to skip rebalance in this round
    */
  override def rebalance(
      currentShardAllocations: Map[ActorRef, IndexedSeq[ShardId]],
      rebalanceInProgress: Set[ShardId],
    ): Future[Set[ShardId]] = {

    val current =
      currentShardAllocations
        .map { (ar, shards) =>
          val addrStr = if ar.path.address.hasLocalScope then cluster.selfMember.address else ar.path.address
          s"""$addrStr=[${shards.mkString(",")}]"""
        }
        .mkString(", ")

    system
      .log
      .warn(s"""
           |-----------------------------
           |Rebalance: Current:[$current] / InProgress: [${rebalanceInProgress.mkString(",")}]
           |-----------------------------
           |""".stripMargin)

    delegate.rebalance(currentShardAllocations, rebalanceInProgress)
  }

  /** Invoked when the location of a new shard is to be decided.
    *
    * @param requester
    *   actor reference to the [[ShardRegion]] that requested the location of the shard, can be returned if preference
    *   should be given to the node where the shard was first accessed
    * @param shardId
    *   the id of the shard to allocate
    * @param currentShardAllocations
    *   all actor refs to `ShardRegion` and their current allocated shards, in the order they were allocated
    * @return
    *   a `Future` of the actor ref of the [[ShardRegion]] that is to be responsible for the shard, must be one of the
    *   references included in the `currentShardAllocations` parameter
    */
  override def allocateShard(
      requester: ActorRef,
      shardId: ShardId,
      currentShardAllocations: Map[ActorRef, IndexedSeq[ShardId]],
    ): Future[ActorRef] = {
    system.log.warn(s"★ ★ ★ AllocateShard: $shardId")
    delegate.allocateShard(requester, shardId, currentShardAllocations)
  }

}
