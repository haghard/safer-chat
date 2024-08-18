// Copyright (c) 2024 by Vadim Bondarev
// This software is licensed under the Apache License, Version 2.0.
// You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0.

package org.apache.pekko.cluster

extension (member: Member) {
  /*
    Member.ageOrdering uses
    if (upNumber == other.upNumber) Member.addressOrdering.compare(address, other.address) < 0 else upNumber < other.upNumber
   */
  def details(): String =
    s"${member.uniqueAddress.address}/UpNum(${member.upNumber})/Ver(${member.appVersion}))"
  // `UpNum` is monotonically growing sequence number which increases each time new incarnation of the process starts.

  def details2(): String =
    s"${member.uniqueAddress.address}:${member.upNumber}"

  def details3(): String =
    s"${member.uniqueAddress.address.host.getOrElse("")},UpNum(${member.upNumber}),Ver(${member.appVersion}))"
}
