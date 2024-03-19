import sbt.*

object AppDependencies {

  private val testScope = "test,it"
  private val mongoVersion = "1.8.0"

  val compile = Seq(
    "uk.gov.hmrc.mongo"            %% "hmrc-mongo-work-item-repo-play-28"      % mongoVersion,
    "uk.gov.hmrc"                  %% "bootstrap-backend-play-28"              % "8.0.0",
    "org.typelevel"                %% "cats-core"                              % "2.9.0"
  )

  val test = Seq(
    "org.scalatestplus.play"       %% "scalatestplus-play"      % "5.1.0"      % testScope,
    "com.github.tomakehurst"        % "wiremock-standalone"     % "2.27.2"     % testScope,
    "org.scalatestplus"            %% "scalatestplus-mockito"   % "1.0.0-M2"   % testScope,
    "uk.gov.hmrc.mongo"            %% "hmrc-mongo-test-play-28" % mongoVersion % testScope,
    "com.vladsch.flexmark"          % "flexmark-all"            % "0.35.10"    % testScope,
    "uk.gov.hmrc"                  %% "bootstrap-test-play-28"  % "8.0.0"      % testScope,
    "org.mockito"                   % "mockito-core"            % "5.3.1"      % testScope,
    "com.fasterxml.jackson.module" %% "jackson-module-scala"    % "2.17.0"     % testScope
  )
}
