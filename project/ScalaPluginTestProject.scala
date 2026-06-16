import java.io.File
import sbt.*
import sbt.CompositeProject
import sbt.Keys.autoCompilerPlugins
import sbt.Project
import sbt.ProjectReference

object ScalaPluginTestProject {

  def compilationProject(configureFunc: Project => Project): CompositeProject = {
    val pathToTests = "akka-javasdk-scala-plugin-tests"

    new CompositeProject {

      def componentProjects: Seq[Project] = innerProjects :+ root

      lazy val root =
        Project(id = pathToTests, base = file(pathToTests))
          .disablePlugins(Publish)
          .aggregate(innerProjects.map(p => p: ProjectReference): _*)

      lazy val innerProjects =
        findProjects
          .map { dir =>
            Project(s"$pathToTests-" + dir.getName, dir)
              .disablePlugins(Publish)
              .settings(
                autoCompilerPlugins := true
              )
          }
          .map(configureFunc)

      def findProjects: Seq[File] = {
        val dir = file(pathToTests)
        if (dir.exists() && dir.isDirectory)
          dir.listFiles().filter(f => f.isDirectory && f.getName.endsWith("descriptors")).toSeq
        else
          Seq.empty
      }
    }
  }
}
