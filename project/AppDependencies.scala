import sbt.*

object AppDependencies {

  private val testScope = "test,it"
  private val mongoVersion = "1.1.0"

  val compile = Seq(
    "uk.gov.hmrc" %% "bootstrap-backend-play-28" % "7.15.0",
    "uk.gov.hmrc.mongo" %% "hmrc-mongo-work-item-repo-play-28" % mongoVersion,
    "org.typelevel" %% "cats-core" % "2.9.0"
  )

  val test = Seq(
    "org.scalatestplus.play" %% "scalatestplus-play" % "5.1.0" % testScope,
    "com.github.tomakehurst" % "wiremock-standalone" % "2.27.2" % testScope,
    "org.mockito" % "mockito-scala_2.13" % "1.17.29" % testScope,
    "org.mockito" % "mockito-scala-scalatest_2.13" % "1.17.29" % testScope,
    "uk.gov.hmrc.mongo" %% "hmrc-mongo-test-play-28" % mongoVersion % testScope,
    "com.vladsch.flexmark" % "flexmark-all" % "0.35.10"  % testScope,
    "uk.gov.hmrc" %% "bootstrap-test-play-28" % "7.15.0" % testScope,
    "com.github.tomakehurst" % "wiremock-standalone" % "2.27.2" % testScope,
  )
}
