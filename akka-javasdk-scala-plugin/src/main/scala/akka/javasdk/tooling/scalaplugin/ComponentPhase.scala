/*
 * Copyright (C) 2021-2026 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.javasdk.tooling.scalaplugin

import java.io.File
import java.io.PrintWriter
import java.nio.file.Files

import scala.collection.mutable
import scala.tools.nsc.Global
import scala.tools.nsc.Phase
import scala.tools.nsc.plugins.PluginComponent

class ComponentPhase(plugin: ComponentPlugin, val global: Global) extends PluginComponent {
  import global._

  val runsAfter: List[String] = List("typer")
  override val runsBefore: List[String] = List("patmat")
  val phaseName: String = "akka-component-detection"

  // Annotation FQNs — must match ComponentAnnotationProcessor.java
  private val HttpEndpointAnnotation = "akka.javasdk.annotations.http.HttpEndpoint"
  private val GrpcEndpointAnnotation = "akka.javasdk.annotations.GrpcEndpoint"
  private val McpEndpointAnnotation = "akka.javasdk.annotations.mcp.McpEndpoint"
  private val ComponentAnnotation = "akka.javasdk.annotations.Component"
  private val SetupAnnotation = "akka.javasdk.annotations.Setup"

  // Component type keys — must match ComponentAnnotationProcessor.java and ComponentLocator.scala
  private val HttpEndpointKey = "http-endpoint"
  private val GrpcEndpointKey = "grpc-endpoint"
  private val McpEndpointKey = "mcp-endpoint"
  private val EventSourcedEntityKey = "event-sourced-entity"
  private val KeyValueEntityKey = "key-value-entity"
  private val TimedActionKey = "timed-action"
  private val ConsumerKey = "consumer"
  private val ViewKey = "view"
  private val WorkflowKey = "workflow"
  private val AgentKey = "agent"
  private val AutonomousAgentKey = "autonomous-agent"
  private val ServiceSetupKey = "service-setup"

  private val AllComponentTypeKeys: List[String] = List(
    HttpEndpointKey,
    GrpcEndpointKey,
    McpEndpointKey,
    EventSourcedEntityKey,
    KeyValueEntityKey,
    TimedActionKey,
    ConsumerKey,
    ViewKey,
    WorkflowKey,
    AgentKey,
    AutonomousAgentKey)

  // Maps well-known Akka SDK base classes to their component type key.
  // Must match the switch in ComponentAnnotationProcessor.java#recurseForComponentType.
  private val SuperclassToComponentType: Map[String, String] = Map(
    "akka.javasdk.eventsourcedentity.EventSourcedEntity" -> EventSourcedEntityKey,
    "akka.javasdk.keyvalueentity.KeyValueEntity" -> KeyValueEntityKey,
    "akka.javasdk.workflow.Workflow" -> WorkflowKey,
    "akka.javasdk.timedaction.TimedAction" -> TimedActionKey,
    "akka.javasdk.consumer.Consumer" -> ConsumerKey,
    "akka.javasdk.view.View" -> ViewKey,
    "akka.javasdk.agent.Agent" -> AgentKey,
    "akka.javasdk.agent.autonomous.AutonomousAgent" -> AutonomousAgentKey)

  def newPhase(prev: Phase): Phase = new StdPhase(prev) {

    private val componentsByType = mutable.Map.empty[String, mutable.Set[String]]
    private var setupClass: Option[String] = None

    def apply(unit: CompilationUnit): Unit =
      unit.body.foreach {
        case cd: ClassDef => processClass(cd)
        case _            =>
      }

    private def processClass(cd: ClassDef): Unit = {
      val sym = cd.symbol
      // Skip traits, abstract classes, and the synthetic companion module classes
      if (!sym.isClass || sym.isTrait || sym.isAbstract || sym.isModuleClass) return

      def hasAnnotation(fqn: String): Boolean =
        sym.annotations.exists(_.symbol.fullName == fqn)

      if (hasAnnotation(SetupAnnotation)) {
        setupClass match {
          case Some(existing) =>
            reporter.error(
              cd.pos,
              s"More than one class annotated with @Setup is not allowed. " +
              s"Already found: $existing, also found: ${binaryClassName(sym)}")
          case None =>
            setupClass = Some(binaryClassName(sym))
        }
      } else if (hasAnnotation(HttpEndpointAnnotation)) {
        addComponent(HttpEndpointKey, binaryClassName(sym))
      } else if (hasAnnotation(GrpcEndpointAnnotation)) {
        addComponent(GrpcEndpointKey, binaryClassName(sym))
      } else if (hasAnnotation(McpEndpointAnnotation)) {
        addComponent(McpEndpointKey, binaryClassName(sym))
      } else if (hasAnnotation(ComponentAnnotation)) {
        resolveComponentType(sym, sym) match {
          case Right(componentType) => addComponent(componentType, binaryClassName(sym))
          case Left(errorMsg)       => reporter.error(cd.pos, errorMsg)
        }
      }
    }

    // Walks the superclass hierarchy to determine the Akka SDK component type,
    // mirroring ComponentAnnotationProcessor.java#recurseForComponentType.
    private def resolveComponentType(annotated: Symbol, current: Symbol): Either[String, String] = {
      val parents = current.info.parents
      if (parents.isEmpty)
        return Left(
          s"Unknown supertype for class [${annotated.fullName}] annotated with @Component: " +
          "reached top of hierarchy without finding a known Akka SDK component base class.")

      parents.find(p => SuperclassToComponentType.contains(p.typeSymbol.fullName)) match {
        case Some(matched) =>
          Right(SuperclassToComponentType(matched.typeSymbol.fullName))
        case None =>
          val relevant =
            parents.filterNot(p => Set("java.lang.Object", "scala.Any", "scala.AnyRef").contains(p.typeSymbol.fullName))
          relevant
            .map(p => resolveComponentType(annotated, p.typeSymbol))
            .collectFirst { case r @ Right(_) => r }
            .getOrElse(Left(s"Unknown supertype for class [${annotated.fullName}] annotated with @Component: " +
            "no known Akka SDK component base class found in hierarchy."))
      }
    }

    private def addComponent(componentType: String, className: String): Unit =
      componentsByType.getOrElseUpdate(componentType, mutable.Set()).add(className)

    // Returns the JVM binary name: dots for packages, $ for nested classes.
    private def binaryClassName(sym: Symbol): String = {
      val owner = sym.owner
      if (owner == NoSymbol || owner.isPackageClass || owner.isPackage)
        sym.fullName
      else
        binaryClassName(owner) + "$" + sym.name.toString
    }

    override def run(): Unit = {
      super.run()
      if (componentsByType.nonEmpty || setupClass.isDefined)
        writeDescriptor(componentsByType.toMap.map { case (k, v) => k -> v.toList }, setupClass)
    }

    private def writeDescriptor(components: Map[String, List[String]], setup: Option[String]): Unit = {
      if (plugin.groupId == null || plugin.artifactId == null) {
        reporter.error(
          NoPosition,
          "Akka SDK Scala plugin: annotated components found but " +
          "-P:akka-component-plugin:groupId=<id> and " +
          "-P:akka-component-plugin:artifactId=<id> compiler options were not provided.")
        return
      }

      val outputDir: File = global.settings.outputDirs.getSingleOutput match {
        case Some(vd) => vd.file
        case None =>
          reporter.warning(
            NoPosition,
            "Akka SDK Scala plugin: multiple output directories detected; using first output directory.")
          global.settings.outputDirs.outputs.head._2.file
      }

      val descriptorRelPath =
        s"META-INF/akka-javasdk-components_${plugin.groupId}_${plugin.artifactId}.conf"
      val descriptorFile = new File(outputDir, descriptorRelPath)

      // Read existing descriptor to support incremental compilation:
      // unchanged files are not recompiled, so their entries live only in the existing file.
      val (existingComponents, existingSetup) =
        if (descriptorFile.exists()) readDescriptor(descriptorFile)
        else (Map.empty[String, List[String]], None)

      val mergedComponents: Map[String, List[String]] = AllComponentTypeKeys.flatMap { key =>
        val merged = (existingComponents.getOrElse(key, Nil).toSet ++
          components.getOrElse(key, Nil).toSet).toList.sorted
        if (merged.nonEmpty) Some(key -> merged) else None
      }.toMap

      val mergedSetup = setup.orElse(existingSetup)

      val summary = components.map { case (k, vs) => s"${vs.size} $k" }.mkString(", ")
      reporter.echo(s"Akka SDK Scala plugin detected components: $summary")
      reporter.echo(s"Akka SDK Scala plugin writing component descriptor: $descriptorFile")

      descriptorFile.getParentFile.mkdirs()
      writeHocon(descriptorFile, mergedComponents, mergedSetup)
    }

    // Reads back the descriptor we previously wrote, to merge with the current round's findings.
    // The format is the simple HOCON produced by writeHocon below.
    private def readDescriptor(file: File): (Map[String, List[String]], Option[String]) = {
      val content = new String(Files.readAllBytes(file.toPath))

      val components = extractBlock(content, "components") match {
        case Some(block) =>
          // Each entry looks like:  http-endpoint=[\n  "class"\n]
          val listRe = """(?s)([\w-]+)\s*=\s*\[(.*?)\]""".r
          val stringRe = """"([^"]+)"""".r
          listRe
            .findAllMatchIn(block)
            .map { m =>
              val key = m.group(1)
              val values = stringRe.findAllMatchIn(m.group(2)).map(_.group(1)).toList
              key -> values
            }
            .toMap
        case None => Map.empty[String, List[String]]
      }

      val setup = (ServiceSetupKey + """\\s*=\\s*"([^"]+)"""").r
        .findFirstMatchIn(content)
        .map(_.group(1))

      (components, setup)
    }

    // Balanced-brace extraction: finds `keyword { ... }` and returns the inner content.
    private def extractBlock(content: String, keyword: String): Option[String] = {
      val start = content.indexOf(keyword)
      if (start < 0) return None
      val braceOpen = content.indexOf('{', start + keyword.length)
      if (braceOpen < 0) return None
      var depth = 1
      var i = braceOpen + 1
      while (i < content.length && depth > 0) {
        content.charAt(i) match {
          case '{' => depth += 1
          case '}' => depth -= 1
          case _   =>
        }
        i += 1
      }
      if (depth == 0) Some(content.substring(braceOpen + 1, i - 1)) else None
    }

    // Writes a HOCON descriptor in the same format as ComponentAnnotationProcessor.java.
    private def writeHocon(file: File, components: Map[String, List[String]], setup: Option[String]): Unit = {
      val sb = new StringBuilder
      sb.append("akka {\n")
      sb.append("    javasdk {\n")
      if (components.nonEmpty) {
        sb.append("        components {\n")
        for (key <- AllComponentTypeKeys if components.contains(key)) {
          sb.append(s"            $key=[\n")
          components(key).foreach(cls => sb.append(s"""                "$cls"\n"""))
          sb.append("            ]\n")
        }
        sb.append("        }\n")
      }
      setup.foreach(cls => sb.append(s"""        $ServiceSetupKey="$cls"\n"""))
      sb.append("    }\n")
      sb.append("}\n")
      val writer = new PrintWriter(file)
      try writer.print(sb.toString())
      finally writer.close()
    }
  }
}
