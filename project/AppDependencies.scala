import sbt._

object AppDependencies {
  
  private val scalatestplusVersion = "3.1.3"
  private val mockitoVersion = "3.3.3"
  private val wireMockVersion = "2.26.3"
  private val customsApiCommonVersion = "1.51.0"
  private val workItemRepoVersion = "7.6.0-play-26"
  private val reactiveMongoTestVersion = "4.21.0-play-26"

  private val testScope = "test,it"

  val scalaTestPlusPlay = "org.scalatestplus.play" %% "scalatestplus-play" % scalatestplusVersion % testScope
  val wireMock = "com.github.tomakehurst" % "wiremock-jre8" % wireMockVersion % testScope
  val mockito =  "org.mockito" % "mockito-core" % mockitoVersion % testScope
  val customsApiCommon = "uk.gov.hmrc" %% "customs-api-common" % customsApiCommonVersion
  val workItemRepo = "uk.gov.hmrc" %% "work-item-repo" % workItemRepoVersion
  val customsApiCommonTests = "uk.gov.hmrc" %% "customs-api-common" % customsApiCommonVersion % testScope classifier "tests"
  val reactiveMongoTest = "uk.gov.hmrc" %% "reactivemongo-test" % reactiveMongoTestVersion % testScope
}
