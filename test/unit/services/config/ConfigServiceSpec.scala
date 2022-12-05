/*
 * Copyright 2022 HM Revenue & Customs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package unit.services.config

import com.typesafe.config.{Config, ConfigFactory}
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar
import play.api.Configuration
import uk.gov.hmrc.customs.api.common.config.ConfigValidatedNelAdaptor
import uk.gov.hmrc.customs.notification.domain.NotificationQueueConfig
import uk.gov.hmrc.customs.notification.services.config.ConfigService
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig
import util.UnitSpec
import unit.logging.StubCdsLogger
import util.TestData.basicAuthTokenValue

import scala.concurrent.duration._
import scala.language.postfixOps

class ConfigServiceSpec extends UnitSpec with MockitoSugar with Matchers {

  private val validAppConfig: Config = ConfigFactory.parseString(
    s"""
      |{
      |auth.token.internal = "$basicAuthTokenValue"
      |
      |retry.poller.enabled = true
      |retry.poller.interval.milliseconds = 700
      |retry.poller.retryAfterFailureInterval.seconds = 2
      |retry.poller.inProgressRetryAfter.seconds = 3
      |retry.poller.instances = 3
      |non.blocking.retry.after.minutes = 10
      |unblock.poller.enabled = true
      |unblock.poller.interval.milliseconds = 400
      |
      |ttlInSeconds = 1
      |
      |internal.clientIds.0 = ClientIdOne
      |internal.clientIds.1 = ClientIdTwo
      |
      |hotfix.translate = "old:new"
      |
      |  microservice {
      |    services {
      |      notification-queue {
      |        host = localhost
      |        port = 9648
      |        context = "/queue"
      |      }
      |      customs-notification-metrics {
      |        host = localhost
      |        port = 9827
      |        context = /log-times
      |      }
      |    }
      |  }
      |}
    """.stripMargin)

  private val validMandatoryOnlyAppConfig: Config = ConfigFactory.parseString(
    """
      |{
      |retry.poller.enabled = true
      |retry.poller.interval.milliseconds = 700
      |retry.poller.retryAfterFailureInterval.seconds = 2
      |retry.poller.inProgressRetryAfter.seconds = 3
      |retry.poller.instances = 3
      |non.blocking.retry.after.minutes = 10
      |unblock.poller.enabled = true
      |unblock.poller.interval.milliseconds = 400
      |
      |ttlInSeconds = 1
      |
      |hotfix.translate = "old:new"
      |
      |  microservice {
      |    services {
      |      notification-queue {
      |        host = localhost
      |        port = 9648
      |        context = "/queue"
      |      }
      |      customs-notification-metrics {
      |        host = localhost
      |        port = 9827
      |        context = /log-times
      |      }
      |    }
      |  }
      |}
    """.stripMargin)

  private val emptyAppConfig: Config = ConfigFactory.parseString("")

  private def testServicesConfig(configuration: Configuration) = new ServicesConfig(configuration) {}

  private val validServicesConfig = new Configuration(validAppConfig)
  private val mandatoryOnlyServicesConfig = new Configuration(validMandatoryOnlyAppConfig)
  private val emptyServicesConfig = new Configuration(emptyAppConfig)

  private val stubCdsLogger = StubCdsLogger()

  "ConfigService" should {
    "return config as object model when configuration is valid" in {
      val actual = configService(validServicesConfig)

      actual.maybeBasicAuthToken shouldBe Some(basicAuthTokenValue)
      actual.notificationQueueConfig shouldBe NotificationQueueConfig("http://localhost:9648/queue")
      actual.notificationConfig.internalClientIds shouldBe Seq("ClientIdOne", "ClientIdTwo")
      actual.notificationConfig.ttlInSeconds shouldBe 1
      actual.notificationConfig.retryPollerEnabled shouldBe true
      actual.notificationConfig.retryPollerInterval shouldBe (700 milliseconds)
      actual.notificationConfig.retryPollerAfterFailureInterval shouldBe (2 seconds)
      actual.notificationConfig.retryPollerInProgressRetryAfter shouldBe (3 seconds)
      actual.notificationConfig.retryPollerInstances shouldBe 3
      actual.unblockPollerConfig.pollerInterval shouldBe (400 milliseconds)
    }

    "return config as object model when configuration is valid and contains only mandatory values" in {
      val actual = configService(mandatoryOnlyServicesConfig)

      actual.maybeBasicAuthToken shouldBe None
      actual.notificationQueueConfig shouldBe NotificationQueueConfig("http://localhost:9648/queue")
      actual.notificationConfig.ttlInSeconds shouldBe 1
      actual.unblockPollerConfig.pollerInterval shouldBe (400 milliseconds)
      actual.notificationConfig.retryPollerEnabled shouldBe true
      actual.notificationConfig.retryPollerInterval shouldBe (700 milliseconds)
      actual.notificationConfig.retryPollerAfterFailureInterval shouldBe (2 seconds)
      actual.notificationConfig.retryPollerInProgressRetryAfter shouldBe (3 seconds)
      actual.notificationConfig.retryPollerInstances shouldBe 3
    }

    "throw an exception when configuration is invalid, that contains AGGREGATED error messages" in {
      val expected = """
                       |Could not find config key 'notification-queue.host'
                       |Service configuration not found for key: notification-queue.context
                       |Could not find config key 'ttlInSeconds'
                       |Could not find config key 'retry.poller.enabled'
                       |Could not find config key 'retry.poller.interval.milliseconds'
                       |Could not find config key 'retry.poller.retryAfterFailureInterval.seconds'
                       |Could not find config key 'retry.poller.inProgressRetryAfter.seconds'
                       |Could not find config key 'retry.poller.instances'
                       |Could not find config key 'non.blocking.retry.after.minutes'
                       |Could not find config key 'hotfix.translate'
                       |Could not find config key 'customs-notification-metrics.host'
                       |Service configuration not found for key: customs-notification-metrics.context
                       |Could not find config key 'unblock.poller.enabled'
                       |Could not find config key 'unblock.poller.interval.milliseconds'""".stripMargin

      val caught = intercept[IllegalStateException]{ configService(emptyServicesConfig) }

      caught.getMessage shouldBe expected
    }
  }

  private def configService(conf: Configuration) =
    new ConfigService(new ConfigValidatedNelAdaptor(testServicesConfig(conf), conf), stubCdsLogger)

}
