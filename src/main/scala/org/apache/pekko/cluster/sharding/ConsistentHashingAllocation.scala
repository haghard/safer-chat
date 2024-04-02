// Copyright (c) 2024 by Vadim Bondarev
// This software is licensed under the Apache License, Version 2.0.
// You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0.

package org.apache.pekko.cluster.sharding

import scala.collection.immutable
import scala.concurrent.Future

import org.apache.pekko.actor.*
import org.apache.pekko.cluster.ClusterEvent.CurrentClusterState
import org.apache.pekko.cluster.*
import org.apache.pekko.cluster.sharding.ShardCoordinator.ActorSystemDependentAllocationStrategy
import org.apache.pekko.cluster.sharding.ShardRegion.ShardId
import org.apache.pekko.event.*
import org.apache.pekko.routing.ConsistentHash

object ConsistentHashingAllocation {
  val empty = Future.successful(Set.empty[ShardId])
}

final class ConsistentHashingAllocation(rebalanceLimit: Int)
    extends ActorSystemDependentAllocationStrategy
    with ClusterShardAllocationMixin {

  import ConsistentHashingAllocation.empty

  private var cluster: Cluster = scala.compiletime.uninitialized
  private var log0: LoggingAdapter = scala.compiletime.uninitialized

  private val virtualNodesFactor = 3
  private var hashedByNodes: Vector[Address] = Vector.empty
  private var consistentHashing: ConsistentHash[Address] = ConsistentHash(Nil, virtualNodesFactor)

  override def start(system: ActorSystem): Unit = {
    cluster = Cluster(system)
    log0 = Logging(system, classOf[ConsistentHashingAllocation])
  }

  protected def log: LoggingAdapter = log0

  override protected def clusterState: CurrentClusterState = cluster.state

  override protected def selfMember: Member = cluster.selfMember

  override def allocateShard(
      requester: ActorRef,
      shardId: ShardId,
      currentShardAllocations: Map[ActorRef, immutable.IndexedSeq[ShardId]],
    ): Future[ActorRef] = {
    val nodes = nodesForRegions(currentShardAllocations)
    updateHashing(nodes)
    val node = consistentHashing.nodeFor(shardId)
    log.info(s"Allocate Shard($shardId) on ${node.host.getOrElse("")}")

    currentShardAllocations.keysIterator.find(region => nodeForRegion(region) == node) match {
      case Some(region) =>
        Future.successful(region)
      case None =>
        throw new IllegalStateException(s"currentShardAllocations should include region for node [$node]")
    }
  }

  override def rebalance(
      currentShardAllocations: Map[ActorRef, immutable.IndexedSeq[ShardId]],
      rebalanceInProgress: Set[ShardId],
    ): Future[Set[ShardId]] = {

    val sortedRegionEntries =
      regionEntriesFor(currentShardAllocations).toVector.sorted(ClusterShardAllocationMixin.shardSuitabilityOrdering)

    if (isAGoodTimeToRebalance(sortedRegionEntries)) {
      val nodes = nodesForRegions(currentShardAllocations)
      updateHashing(nodes)

      val regionByNode = currentShardAllocations.keysIterator.map(region => nodeForRegion(region) -> region).toMap

      var result = Set.empty[String]

      def lessThanLimit(): Boolean =
        rebalanceLimit <= 0 || result.size < rebalanceLimit

      currentShardAllocations
        // deterministic order, at least easier to test
        .toVector
        .sortBy { case (region, _) => nodeForRegion(region) }(Address.addressOrdering)
        .foreach {
          case (currentRegion, shardIds) =>
            shardIds.foreach { shardId =>
              if (lessThanLimit() && !rebalanceInProgress.contains(shardId)) {
                val node = consistentHashing.nodeFor(shardId)
                regionByNode.get(node) match {
                  case Some(region) =>
                    if (region != currentRegion) {
                      log.debug(
                        "Rebalance needed for shard [{}], from [{}] to [{}]",
                        shardId,
                        nodeForRegion(currentRegion),
                        node,
                      )
                      result += shardId
                    }
                  case None =>
                    throw new IllegalStateException(s"currentShardAllocations should include region for node [$node]")
                }
              }
            }
        }

      Future.successful(result)
    } else {
      empty
    }
  }

  private def nodesForRegions(currentShardAllocations: Map[ActorRef, immutable.IndexedSeq[ShardId]]): Vector[Address] =
    currentShardAllocations
      .keysIterator
      .map(nodeForRegion)
      .toVector

  private def nodeForRegion(region: ActorRef): Address =
    if (region.path.address.hasLocalScope) selfMember.address else region.path.address

  private def updateHashing(nodes: Vector[Address]): Unit = {
    val sortedNodes = nodes.sorted
    if (sortedNodes != hashedByNodes) {
      if (log.isDebugEnabled)
        log.debug("Update consistent hashing nodes [{}]", sortedNodes.mkString(", "))
      hashedByNodes = sortedNodes
      consistentHashing = ConsistentHash(sortedNodes, virtualNodesFactor)
    }
  }
}
