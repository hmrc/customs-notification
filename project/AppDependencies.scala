import sbt.*

object AppDependencies {
  object Version {
    val Bootstrap = "8.1.0"
  }

  val compile = Seq(
    "uk.gov.hmrc" %% "bootstrap-backend-play-30" % Version.Bootstrap,
    "uk.gov.hmrc.mongo" %% "hmrc-mongo-work-item-repo-play-30" % "1.6.0",
    "org.typelevel" %% "cats-core" % "2.9.0"
  )

  val test = Seq(
    "org.mockito" % "mockito-scala-scalatest_2.13" % "1.17.29" % Test,
    "org.scalatest" %% "scalatest" % "3.2.17" % Test,
    "uk.gov.hmrc" %% "bootstrap-test-play-30" % Version.Bootstrap
  )
}
