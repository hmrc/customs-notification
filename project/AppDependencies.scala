import sbt.*

object AppDependencies {

  private val mongoVersion = "2.4.0"
  private val boostrapVersion = "9.8.0"
  private val playVersion = "play-30"

  val compile = Seq(
    "uk.gov.hmrc.mongo"            %% s"hmrc-mongo-work-item-repo-$playVersion"      % mongoVersion,
    "uk.gov.hmrc"                  %% s"bootstrap-backend-$playVersion"              % boostrapVersion,
    "org.typelevel"                %% "cats-core"                                    % "2.10.0"
  )

  val test = Seq(
    "org.scalatestplus.play"       %% "scalatestplus-play"            % "7.0.1"            % Test,
    "org.wiremock"                  % "wiremock-standalone"           % "3.11.0"            % Test,
    "org.scalatestplus"            %% "scalatestplus-mockito"         % "1.0.0-M2"         % Test,
    "uk.gov.hmrc.mongo"            %% s"hmrc-mongo-test-$playVersion" % mongoVersion       % Test,
    "uk.gov.hmrc"                  %% s"bootstrap-test-$playVersion"  % boostrapVersion    % Test,
    "com.fasterxml.jackson.module" %% "jackson-module-scala"          % "2.17.0"           % Test
  )
}
