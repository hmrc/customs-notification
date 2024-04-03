import sbt.*
import sbt.Keys.*
import sbt.Tests.{Group, SubProcess}
import uk.gov.hmrc.DefaultBuildSettings
import uk.gov.hmrc.DefaultBuildSettings.addTestReportOption
import uk.gov.hmrc.gitstamp.GitStampPlugin.*
import uk.gov.hmrc.sbtdistributables.SbtDistributablesPlugin.*

import scala.language.postfixOps

name := "customs-notification"

lazy val ComponentTest = config("component") extend Test
lazy val CdsIntegrationComponentTest = config("it") extend Test

val testConfig = Seq(ComponentTest, CdsIntegrationComponentTest, Test)

def forkedJvmPerTestConfig(tests: Seq[TestDefinition], packages: String*): Seq[Group] =
  tests.groupBy(_.name.takeWhile(_ != '.')).filter(packageAndTests => packages contains packageAndTests._1) map {
    case (packg, theTests) =>
      Group(packg, theTests, SubProcess(ForkOptions()))
  } toSeq

lazy val testAll = TaskKey[Unit]("test-all")
lazy val allTest = Seq(testAll := (ComponentTest / test)
  .dependsOn((CdsIntegrationComponentTest / test).dependsOn(Test / test)).value)

lazy val microservice = (project in file("."))
  .enablePlugins(PlayScala)
  .enablePlugins(SbtAutoBuildPlugin)
  .enablePlugins(SbtDistributablesPlugin)
  .disablePlugins(sbt.plugins.JUnitXmlReportPlugin)
  .configs(testConfig: _*)
  .settings(scalaVersion := "2.13.10",
    DefaultBuildSettings.targetJvm := "jvm-11",
    IntegrationTest / parallelExecution := false,
    Test / parallelExecution := false)
  .settings(
    commonSettings,
    unitTestSettings,
    integrationComponentTestSettings,
    allTest,
    scoverageSettings
  )
  .settings(majorVersion := 0)
  .settings(playDefaultPort := 9821)
  .settings(scalacOptions ++= List(
    "-Yrangepos",
    "-Xlint:-missing-interpolator,_",
    "-feature",
    "-unchecked",
    "-language:implicitConversions",
    "-Wconf:cat=unused-imports&src=target/scala-2\\.13/routes/.*:s"
  )
  )

lazy val unitTestSettings =
  inConfig(Test)(Defaults.testTasks) ++
    Seq(
      Test / testOptions := Seq(Tests.Filter(unitTestFilter)),
      Test / unmanagedSourceDirectories := Seq((Test / baseDirectory).value / "test"),
      addTestReportOption(Test, "test-reports")
    )

lazy val integrationComponentTestSettings =
  inConfig(CdsIntegrationComponentTest)(Defaults.testTasks) ++
    Seq(
      CdsIntegrationComponentTest / testOptions := Seq(Tests.Filter(integrationComponentTestFilter)),
      CdsIntegrationComponentTest / parallelExecution := false,
      addTestReportOption(CdsIntegrationComponentTest, "int-comp-test-reports"),
      CdsIntegrationComponentTest / testGrouping := forkedJvmPerTestConfig((Test / definedTests).value, "integration", "component")
    )

lazy val commonSettings: Seq[Setting[_]] = publishingSettings ++ gitStampSettings

lazy val scoverageSettings: Seq[Setting[_]] = Seq(
  coverageExcludedPackages := List(
    "<empty>"
    , "Reverse.*"
    , "uk\\.gov\\.hmrc\\.customs\\.notification\\.model\\..*"
    , "uk\\.gov\\.hmrc\\.customs\\.notification\\.domain\\..*"
    , ".*(Reverse|AuthService|BuildInfo|Routes).*"
  ).mkString(";"),
  coverageMinimumStmtTotal := 95,
  coverageFailOnMinimum := true,
  coverageHighlighting := true,
  Test / parallelExecution := false
)

def integrationComponentTestFilter(name: String): Boolean = (name startsWith "integration") || (name startsWith "component")
def unitTestFilter(name: String): Boolean = name startsWith "unit"

scalastyleConfig := baseDirectory.value / "project" / "scalastyle-config.xml"

Compile / unmanagedResourceDirectories += baseDirectory.value / "public"

libraryDependencies ++= AppDependencies.compile ++ AppDependencies.test
// To resolve a bug with version 2.x.x of the scoverage plugin - https://github.com/sbt/sbt/issues/6997
libraryDependencySchemes += "org.scala-lang.modules" %% "scala-xml" % VersionScheme.Always
