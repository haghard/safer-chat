// Copyright (c) 2024 by Vadim Bondarev
// This software is licensed under the Apache License, Version 2.0.
// You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0.

import server.grpc.*

object Main extends Ops {

  def main(args: Array[String]): Unit = {
    val opts: Map[String, String] = argsToOpts(args.toList)
    applySystemProperties(opts)
    Bootstrap.run()
  }
}
