import sbt._

object AppDependencies {
  
  private val scalatestplusVersion = "4.0.3"
  private val mockitoVersion = "3.11.2"
  private val wireMockVersion = "2.28.1"
  private val customsApiCommonVersion = "1.56.0"
  private val workItemRepoVersion = "8.0.0-play-27"
  private val reactiveMongoTestVersion = "5.0.0-play-27"

  private val testScope = "test,it"

  val scalaTestPlusPlay = "org.scalatestplus.play" %% "scalatestplus-play" % scalatestplusVersion % testScope
  val wireMock = "com.github.tomakehurst" % "wiremock-jre8" % wireMockVersion % testScope
  val mockito =  "org.mockito" % "mockito-core" % mockitoVersion % testScope
  val customsApiCommon = "uk.gov.hmrc" %% "customs-api-common" % customsApiCommonVersion
  val workItemRepo = "uk.gov.hmrc" %% "work-item-repo" % workItemRepoVersion
  val customsApiCommonTests = "uk.gov.hmrc" %% "customs-api-common" % customsApiCommonVersion % testScope classifier "tests"
  val reactiveMongoTest = "uk.gov.hmrc" %% "reactivemongo-test" % reactiveMongoTestVersion % testScope
  val silencerPlugin = compilerPlugin("com.github.ghik" % "silencer-plugin" % "1.7.5" cross CrossVersion.full)
  val silencerLib = "com.github.ghik" % "silencer-lib" % "1.7.5" % Provided cross CrossVersion.full
}
