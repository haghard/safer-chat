package org.apache.pekko.cluster
package sharding

import org.apache.pekko.actor.{ ActorRef, Address }
import org.apache.pekko.cluster.sharding.ShardRegion.ShardId
import org.apache.pekko.cluster.sharding.internal.AbstractLeastShardAllocationStrategy
import org.apache.pekko.cluster.sharding.internal.AbstractLeastShardAllocationStrategy.AllocationMap
import org.slf4j.Logger

import scala.concurrent.Future

final class LeastShardNoRebalancingAllocationStrategy(log: Logger) extends AbstractLeastShardAllocationStrategy {

  val emptyRebalanceResult = Future.successful(Set.empty[ShardId])
  val seqNum = new java.util.concurrent.atomic.AtomicLong(0)

  override def allocateShard(
      requester: ActorRef,
      shardId: ShardId,
      currentShardAllocations: AllocationMap,
    ): Future[ActorRef] =
    leastShardAllocation(shardId, currentShardAllocations)
  // onRequester(requester, shardId, currentShardAllocations)
  // roundRobin(shardId, currentShardAllocations)

  def roundRobin(shardId: ShardId, currentShardAllocations: AllocationMap): Future[ActorRef] =
    Future.successful {
      val regions = currentShardAllocations.keySet
      val sortedRegions = regions.toVector.sorted { (x: ActorRef, y: ActorRef) =>
        Address.addressOrdering.compare(x.path.address, y.path.address)
      }
      // val sortedRegions = scala.collection.SortedSet.from(regions)((x: ActorRef, y: ActorRef) => Address.addressOrdering.compare(x.path.address, y.path.address))
      val regionInd = (seqNum.getAndIncrement() % regions.size).toInt
      val candidate = Vector.from(sortedRegions)(regionInd)

      val addrStr = if candidate.path.address.hasLocalScope then selfMember.address else candidate.path.address
      println(s"★ ★ ★ AllocateShard($shardId) on $addrStr)")
      candidate
    }

  def leastShardAllocation(shardId: ShardId, currentShardAllocations: AllocationMap) =
    Future.successful {
      val regionEntries = regionEntriesFor(currentShardAllocations)
      // prefer the node with the least allocated shards
      val (region, _) = mostSuitableRegion(regionEntries)
      val addrStr = if region.path.address.hasLocalScope then selfMember.address else region.path.address
      log.warn(s"★ ★ ★ AllocateShard($shardId) on ${addrStr}")
      region
    }

  def onRequester(
      requester: ActorRef,
      shardId: ShardId,
      currentShardAllocations: AllocationMap,
    ): Future[ActorRef] =
    Future.successful {
      val requesterAddr = if requester.path.address.hasLocalScope then selfMember.address else requester.path.address
      log.info(s"★ ★ ★ AllocateShard($shardId) on $requesterAddr")
      requester
    }

  // Never rebalances
  override def rebalance(
      currentShardAllocations: AllocationMap,
      rebalanceInProgress: Set[ShardId],
    ): Future[Set[ShardId]] =
    emptyRebalanceResult
}
