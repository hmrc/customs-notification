import sbt._

object AppDependencies {
  
  private val hmrcTestVersion = "3.8.0-play-25"
  private val scalaTestVersion = "3.0.6"
  private val scalatestplusVersion = "2.0.1"
  private val mockitoVersion = "2.27.0"
  private val wireMockVersion = "2.23.2"
  private val customsApiCommonVersion = "1.39.0"
  private val workItemRepoVersion = "6.6.0-play-25"
  private val simpleReactiveMongoVersion = "7.19.0-play-25"
  private val reactiveMongoTestVersion = "4.14.0-play-25"
  private val testScope = "test,it"

  val hmrcTest = "uk.gov.hmrc" %% "hmrctest" % hmrcTestVersion % testScope

  val scalaTest = "org.scalatest" %% "scalatest" % scalaTestVersion % testScope

  val scalaTestPlusPlay = "org.scalatestplus.play" %% "scalatestplus-play" % scalatestplusVersion % testScope

  val wireMock = "com.github.tomakehurst" % "wiremock" % wireMockVersion % testScope exclude("org.apache.httpcomponents","httpclient") exclude("org.apache.httpcomponents","httpcore")

  val mockito =  "org.mockito" % "mockito-core" % mockitoVersion % testScope

  val customsApiCommon = "uk.gov.hmrc" %% "customs-api-common" % customsApiCommonVersion

  val workItemRepo = "uk.gov.hmrc" %% "work-item-repo" % workItemRepoVersion

  val customsApiCommonTests = "uk.gov.hmrc" %% "customs-api-common" % customsApiCommonVersion % testScope classifier "tests"

  val simpleReactiveMongo = "uk.gov.hmrc" %% "simple-reactivemongo" % simpleReactiveMongoVersion

  val reactiveMongoTest = "uk.gov.hmrc" %% "reactivemongo-test" % reactiveMongoTestVersion % testScope
}
