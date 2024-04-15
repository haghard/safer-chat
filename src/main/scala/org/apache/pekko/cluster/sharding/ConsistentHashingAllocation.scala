// Copyright (c) 2024 by Vadim Bondarev
// This software is licensed under the Apache License, Version 2.0.
// You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0.

package org.apache.pekko.cluster.sharding

import scala.collection.immutable
import scala.concurrent.Future
import org.apache.pekko.actor.*
import org.apache.pekko.cluster.ClusterEvent.CurrentClusterState
import org.apache.pekko.cluster.*
import org.apache.pekko.cluster.MemberStatus.{ Joining, WeaklyUp }
import org.apache.pekko.cluster.sharding.ShardCoordinator.ActorSystemDependentAllocationStrategy
import org.apache.pekko.cluster.sharding.ShardRegion.ShardId
import org.apache.pekko.cluster.sharding.internal.AbstractLeastShardAllocationStrategy
import org.apache.pekko.cluster.sharding.internal.AbstractLeastShardAllocationStrategy.RegionEntry
import org.apache.pekko.event.*
import org.apache.pekko.routing.ConsistentHash

object ConsistentHashingAllocation {
  val virtualNodesFactor = 3
  val JoiningCluster: Set[MemberStatus] = Set(Joining, WeaklyUp)

  val empty = Future.successful(Set.empty[ShardId])
}

final class ConsistentHashingAllocation(rebalanceLimit: Int) extends ActorSystemDependentAllocationStrategy {

  import ConsistentHashingAllocation.empty

  private var cluster: Cluster = scala.compiletime.uninitialized
  private var log0: LoggingAdapter = scala.compiletime.uninitialized

  private var hashedByNodes: Vector[Address] = Vector.empty
  private var consistentHashing: ConsistentHash[Address] =
    ConsistentHash(Nil, ConsistentHashingAllocation.virtualNodesFactor)

  override def start(system: ActorSystem): Unit = {
    cluster = Cluster(system)
    log0 = Logging(system, classOf[ConsistentHashingAllocation])
  }

  protected def log: LoggingAdapter = log0

  def clusterState: CurrentClusterState = cluster.state

  def selfMember: Member = cluster.selfMember

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
      regionEntriesFor(currentShardAllocations)
        .toVector
        .sorted(AbstractLeastShardAllocationStrategy.ShardSuitabilityOrdering)

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
      consistentHashing = ConsistentHash(sortedNodes, ConsistentHashingAllocation.virtualNodesFactor)
    }
  }

  private def isAGoodTimeToRebalance(regionEntries: Iterable[RegionEntry]): Boolean =
    // Avoid rebalance when rolling update is in progress
    // (This will ignore versions on members with no shard regions, because of sharding role or not yet completed joining)
    regionEntries.headOption match {
      case None => false // empty list of regions, probably not a good time to rebalance...
      case Some(firstRegion) =>
        def allNodesSameVersion() =
          regionEntries.forall(_.member.appVersion == firstRegion.member.appVersion)

        def neededMembersReachable =
          !clusterState.members.exists(m => m.dataCenter == selfMember.dataCenter && clusterState.unreachable(m))

        // No members in same dc joining, we want that to complete before rebalance, such nodes should reach Up soon
        def membersInProgressOfJoining =
          clusterState
            .members
            .exists(m => m.dataCenter == selfMember.dataCenter && ConsistentHashingAllocation.JoiningCluster(m.status))

        allNodesSameVersion() && neededMembersReachable && !membersInProgressOfJoining
    }

  private def regionEntriesFor(currentShardAllocations: Map[ActorRef, immutable.IndexedSeq[ShardId]])
      : Iterable[RegionEntry] = {
    val addressToMember: Map[Address, Member] = clusterState.members.iterator.map(m => m.address -> m).toMap
    currentShardAllocations.flatMap {
      case (region, shardIds) =>
        val regionAddress =
          if (region.path.address.hasLocalScope) selfMember.address
          else region.path.address

        val memberForRegion = addressToMember.get(regionAddress)
        // if the member is unknown (very unlikely but not impossible) because of view not updated yet
        // that node is ignored for this invocation
        memberForRegion.map(member => RegionEntry(region, member, shardIds))
    }
  }

}
