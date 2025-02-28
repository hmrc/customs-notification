import sbt.*

object AppDependencies {

  private val mongoVersion = "2.5.0"
  private val boostrapVersion = "9.10.0"
  private val playVersion = "play-30"

  val compile = Seq(
    "uk.gov.hmrc.mongo"            %% s"hmrc-mongo-work-item-repo-$playVersion"      % mongoVersion,
    "uk.gov.hmrc"                  %% s"bootstrap-backend-$playVersion"              % boostrapVersion,
    "org.typelevel"                %% "cats-core"                                    % "2.13.0"
  )

  val test = Seq(
    "org.scalatestplus.play"       %% "scalatestplus-play"            % "7.0.1" ,
    "org.wiremock"                  % "wiremock-standalone"           % "3.12.0"  ,
    "org.scalatestplus"            %% "scalatestplus-mockito"         % "1.0.0-M2" ,
    "uk.gov.hmrc.mongo"            %% s"hmrc-mongo-test-$playVersion" % mongoVersion,
    "uk.gov.hmrc"                  %% s"bootstrap-test-$playVersion"  % boostrapVersion,
    "com.fasterxml.jackson.module" %% "jackson-module-scala"          % "2.18.2"
  ).map(_ % Test)
}
