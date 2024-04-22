import sbt.*

object AppDependencies {

  private val mongoVersion = "1.8.0"
  private val boostrapVersion = "8.5.0"
  private val playVersion = "play-30"

  val compile = Seq(
    "uk.gov.hmrc.mongo"            %% s"hmrc-mongo-work-item-repo-$playVersion"      % mongoVersion,
    "uk.gov.hmrc"                  %% s"bootstrap-backend-$playVersion"              % boostrapVersion,
    "org.typelevel"                %% "cats-core"                                    % "2.10.0"
  )

  val test = Seq(
    "org.scalatestplus.play"       %% "scalatestplus-play"            % "5.1.0"            % Test,
    "com.github.tomakehurst"        % "wiremock-standalone"           % "2.27.2"           % Test,
    "org.scalatestplus"            %% "scalatestplus-mockito"         % "1.0.0-M2"         % Test,
    "uk.gov.hmrc.mongo"            %% s"hmrc-mongo-test-$playVersion" % mongoVersion       % Test,
    "com.vladsch.flexmark"          % "flexmark-all"                  % "0.35.10"          % Test,
    "uk.gov.hmrc"                  %% s"bootstrap-test-$playVersion"  % boostrapVersion    % Test,
    "org.mockito"                   % "mockito-core"                  % "5.3.1"            % Test,
    "com.fasterxml.jackson.module" %% "jackson-module-scala"          % "2.17.0"           % Test
  )
}
