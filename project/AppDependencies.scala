import sbt._

object AppDependencies {
  
  private val hmrcTestVersion = "3.9.0-play-26"
  private val scalaTestVersion = "3.0.8"
  private val scalatestplusVersion = "3.1.2"
  private val mockitoVersion = "3.1.0"
  private val wireMockVersion = "2.25.1"
  private val customsApiCommonVersion = "1.46.0"
  private val workItemRepoVersion = "6.9.0-play-26"
  private val reactiveMongoTestVersion = "4.16.0-play-26"

  private val forceAkkaVersion     = "2.5.23" //this is the last version that works with ReactiveMongo bootstrap-play-26
  private val forceAkkaHttpVersion = "10.0.15"

  private val testScope = "test,it"

  val hmrcTest = "uk.gov.hmrc" %% "hmrctest" % hmrcTestVersion % testScope
  val scalaTest = "org.scalatest" %% "scalatest" % scalaTestVersion % testScope
  val scalaTestPlusPlay = "org.scalatestplus.play" %% "scalatestplus-play" % scalatestplusVersion % testScope
  val wireMock = "com.github.tomakehurst" % "wiremock-jre8" % wireMockVersion % testScope
  val mockito =  "org.mockito" % "mockito-core" % mockitoVersion % testScope
  val customsApiCommon = "uk.gov.hmrc" %% "customs-api-common" % customsApiCommonVersion
  val workItemRepo = "uk.gov.hmrc" %% "work-item-repo" % workItemRepoVersion
  val customsApiCommonTests = "uk.gov.hmrc" %% "customs-api-common" % customsApiCommonVersion % testScope classifier "tests"
  val reactiveMongoTest = "uk.gov.hmrc" %% "reactivemongo-test" % reactiveMongoTestVersion % testScope

  val overrideAkkaStream = "com.typesafe.akka" %% "akka-stream"    % forceAkkaVersion
  val overrideAkkaProtobuf = "com.typesafe.akka" %% "akka-protobuf"  % forceAkkaVersion
  val overrideAkkaSlf4j = "com.typesafe.akka" %% "akka-slf4j"     % forceAkkaVersion
  val overrideAkkaActor = "com.typesafe.akka" %% "akka-actor"     % forceAkkaVersion
  val overrideAkkaHttpCore = "com.typesafe.akka" %% "akka-http-core" % forceAkkaHttpVersion
}
