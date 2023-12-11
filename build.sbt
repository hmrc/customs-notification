import sbt.Keys.*
import uk.gov.hmrc.DefaultBuildSettings.{addTestReportOption, targetJvm}
import uk.gov.hmrc.gitstamp.GitStampPlugin.*

import scala.language.postfixOps

ThisBuild / majorVersion := 0
ThisBuild / scalaVersion := "2.13.12"
ThisBuild / targetJvm := "jvm-11"

lazy val it = (project in file("it"))
  .enablePlugins(PlayScala)
  .dependsOn(microservice % "test->test;compile->compile")
  .settings(
    commonSettings,
    addTestReportOption(Test, "int-comp-test-reports"),
    publishArtifact := false,
    Test / fork := true
  )

lazy val microservice = (project in file("."))
  .enablePlugins(PlayScala)
  .enablePlugins(SbtAutoBuildPlugin)
  .enablePlugins(SbtDistributablesPlugin)
  .disablePlugins(sbt.plugins.JUnitXmlReportPlugin)
  .settings(
    commonSettings,
    unitTestSettings,
    libraryDependencies ++= AppDependencies.compile ++ AppDependencies.test
  )

lazy val unitTestSettings =
  inConfig(Test)(Defaults.testTasks) ++
    Seq(
      addTestReportOption(Test)
    )
lazy val commonSettings: Seq[Setting[?]] = {
  gitStampSettings ++
    List(
      name := "customs-notification",
      scalastyleConfig := baseDirectory.value / "project" / "scalastyle-config.xml",
      scalacOptions ++= List(
        "-Yrangepos",
        "-Xlint:-missing-interpolator,_",
        "-feature",
        "-language:implicitConversions",
        "-Wconf:cat=unused-imports&src=target/scala-2\\.13/routes/.*:s",
        "-Wconf:cat=scala3-migration:s",
        "-Xsource:3"
      )
    )
}
