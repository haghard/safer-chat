// Copyright (c) 2024 by Vadim Bondarev
// This software is licensed under the Apache License, Version 2.0.
// You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0.

package server.grpc

trait CliArgs {

  def argsToOpts(args: Seq[String]): Map[String, String] = {
    val RegExpr = """(\S+)=(\S+)""".r
    args.collect { case RegExpr(key, value) => key -> value }.toMap
  }

  def applySystemProperties(kvs: Map[String, String]): Unit =
    for (key, value) <- kvs if key.startsWith("-D") do {
      val k = key.substring(2)
      println(s"Set $k: $value")
      sys.props += k -> value
    }
}
