/*
 * Copyright (C) 2021-2026 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.javasdk.tooling.scalaplugin

import scala.tools.nsc.Global
import scala.tools.nsc.plugins.Plugin
import scala.tools.nsc.plugins.PluginComponent

class ComponentPlugin(val global: Global) extends Plugin {
  val name = "akka-component-plugin"
  val description = "Generates Akka SDK component descriptor for Scala components"

  // Set via -P:akka-component-plugin:groupId=<id> and -P:akka-component-plugin:artifactId=<id>
  var groupId: String = null
  var artifactId: String = null

  val components: List[PluginComponent] = List(new ComponentPhase(this, global))

  override def init(options: List[String], error: String => Unit): Boolean = {
    options.foreach {
      case opt if opt.startsWith("groupId=")    => groupId = opt.stripPrefix("groupId=")
      case opt if opt.startsWith("artifactId=") => artifactId = opt.stripPrefix("artifactId=")
      case opt                                  => error(s"Unknown option: $opt")
    }
    true
  }

  override val optionsHelp: Option[String] = Some(
    "  -P:akka-component-plugin:groupId=<id>       Maven/sbt group ID\n" +
    "  -P:akka-component-plugin:artifactId=<id>    Maven/sbt artifact ID")
}
