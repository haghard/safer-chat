import sbt.*

import scala.sys.process.*
import scala.util.*

object SbtUtils {

  def styled(str: String): String =
    scala.Console.CYAN + str + scala.Console.RESET

  def prompt(projectName: String): String =
    gitPrompt.fold(projectPrompt(projectName)) { g =>
      s"$g:${projectPrompt(projectName)}"
    }

  private def projectPrompt(projectName: String): String =
    s"sbt:${styled(projectName)}"

  def projectName(state: State): String =
    Project
      .extract(state)
      .currentRef
      .project

  private def gitPrompt: Option[String] =
    for {
      b <- branch.map(styled)
      h <- hash.map(styled)
    } yield s"git:$b:$h"

  def branch: Option[String] =
    run("git rev-parse --abbrev-ref HEAD")

  private def hash: Option[String] =
    run("git rev-parse --short HEAD")

  def fullGitHash: Option[String] =
    run("git rev-parse HEAD")

  private def run(command: String): Option[String] =
    Try(
      command
        .split(" ")
        .toSeq
        .!!(noopProcessLogger)
        .trim
    ).toOption

  private val noopProcessLogger: ProcessLogger =
    ProcessLogger(_ => (), _ => ())
  
}
