import sbt._

object AppDependencies {

  private val testScope = "test,it"

  val compile = Seq(
    "uk.gov.hmrc" %% "customs-api-common" % "1.57.0",
    "uk.gov.hmrc" %% "simple-reactivemongo" % "8.0.0-play-28",
    "uk.gov.hmrc" %% "work-item-repo" % "8.1.0-play-28",
    "com.github.ghik" % "silencer-lib" % "1.7.5" % Provided cross CrossVersion.full,
    compilerPlugin("com.github.ghik" % "silencer-plugin"    % "1.7.5" cross CrossVersion.full),
  )

  val test = Seq(
    "uk.gov.hmrc" %% "customs-api-common" % "1.57.0" % testScope classifier "tests",
    "org.scalatestplus.play" %% "scalatestplus-play" % "5.1.0" % testScope,
    "com.github.tomakehurst" % "wiremock-standalone" % "2.27.1" % testScope,
    "org.scalatestplus" %% "mockito-3-4" % "3.2.9.0" % testScope,
    "uk.gov.hmrc" %% "reactivemongo-test" % "5.0.0-play-28" % testScope,
    "com.vladsch.flexmark" % "flexmark-all" % "0.35.10"  % testScope
  )
}
