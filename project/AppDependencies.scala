import sbt._

object AppDependencies {

  private val testScope = "test,it"
  private val mongoVersion = "0.74.0"

  val compile = Seq(
    "uk.gov.hmrc" %% "customs-api-common" % "1.58.0",
    "uk.gov.hmrc.mongo" %% "hmrc-mongo-play-28" % mongoVersion,
    "uk.gov.hmrc.mongo" %% "hmrc-mongo-work-item-repo-play-28" % mongoVersion,
  )

  val test = Seq(
    "uk.gov.hmrc" %% "customs-api-common" % "1.57.0" % testScope classifier "tests",
    "org.scalatestplus.play" %% "scalatestplus-play" % "5.1.0" % testScope,
    "com.github.tomakehurst" % "wiremock-standalone" % "2.27.1" % testScope,
    "org.scalatestplus" %% "mockito-3-4" % "3.2.9.0" % testScope,
    "uk.gov.hmrc.mongo" %% "hmrc-mongo-test-play-28" % mongoVersion % testScope,
    "com.vladsch.flexmark" % "flexmark-all" % "0.35.10"  % testScope
  )
}
