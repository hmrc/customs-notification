import sbt.*

object AppDependencies {

  private val mongoVersion = "2.12.0"
  private val boostrapVersion = "10.7.0"
  private val playVersion = "play-30"

  val compile = Seq(
    "uk.gov.hmrc.mongo"            %% s"hmrc-mongo-work-item-repo-$playVersion"      % mongoVersion,
    "uk.gov.hmrc"                  %% s"bootstrap-backend-$playVersion"              % boostrapVersion,
    "org.typelevel"                %% "cats-core"                                    % "2.13.0"
  )

  val test = Seq(

    "org.wiremock"                  % "wiremock-standalone"           % "3.13.2"  ,
    "org.scalatestplus"            %% "mockito-4-11"                  % "3.2.18.0" ,
    "uk.gov.hmrc.mongo"            %% s"hmrc-mongo-test-$playVersion" % mongoVersion,
    "uk.gov.hmrc"                  %% s"bootstrap-test-$playVersion"  % boostrapVersion,
    "com.fasterxml.jackson.module" %% "jackson-module-scala"          % "2.21.3"
  ).map(_ % Test)
}
