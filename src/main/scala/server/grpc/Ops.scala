// Copyright (c) 2024 by Vadim Bondarev
// This software is licensed under the Apache License, Version 2.0.
// You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0.

package server.grpc

trait Ops {

  val Opt = """(\S+)=(\S+)""".r

  def argsToOpts(args: Seq[String]): Map[String, String] =
    args.collect { case Opt(key, value) => key -> value }.toMap

  def applySystemProperties(options: Map[String, String]): Unit =
    for ((key, value) <- options if key.startsWith("-D")) {
      val k = key.substring(2)
      println(s"Set $k: $value")
      System.setProperty(key.substring(2), value)
    }
}
