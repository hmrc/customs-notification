import sbt.*

object AppDependencies {

  private val mongoVersion = "2.2.0"
  private val boostrapVersion = "8.6.0"
  private val playVersion = "play-30"

  val compile = Seq(
    "uk.gov.hmrc.mongo"            %% s"hmrc-mongo-work-item-repo-$playVersion"      % mongoVersion,
    "uk.gov.hmrc"                  %% s"bootstrap-backend-$playVersion"              % boostrapVersion,
    "org.typelevel"                %% "cats-core"                                    % "2.12.0"
  )

  val test = Seq(
    "org.scalatestplus.play"       %% "scalatestplus-play"            % "7.0.1"            % Test,
    "org.wiremock"                  % "wiremock-standalone"           % "3.9.1"            % Test,
    "org.scalatestplus"            %% "scalatestplus-mockito"         % "1.0.0-M2"         % Test,
    "uk.gov.hmrc.mongo"            %% s"hmrc-mongo-test-$playVersion" % mongoVersion       % Test,
    "uk.gov.hmrc"                  %% s"bootstrap-test-$playVersion"  % boostrapVersion    % Test,
    "com.fasterxml.jackson.module" %% "jackson-module-scala"          % "2.17.2"           % Test
  )
}
