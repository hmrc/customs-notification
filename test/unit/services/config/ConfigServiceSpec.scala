/*
 * Copyright 2019 HM Revenue & Customs
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
import org.scalatest.Matchers
import org.scalatest.mockito.MockitoSugar
import play.api.{Configuration, Environment, Mode}
import uk.gov.hmrc.customs.api.common.config.{ConfigValidatedNelAdaptor, ServicesConfig}
import uk.gov.hmrc.customs.notification.domain.NotificationQueueConfig
import uk.gov.hmrc.customs.notification.services.config.ConfigService
import uk.gov.hmrc.play.test.UnitSpec
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
      |push.polling.delay.duration.milliseconds = 5000
      |push.polling.enabled = true
      |push.lock.duration.milliseconds = 1000
      |push.fetch.maxRecords = 50
      |push.retry.enabled = true
      |push.retry.initialPollingInterval.milliseconds = 700
      |push.retry.retryAfterFailureInterval.seconds = 2
      |push.retry.inProgressRetryAfter.seconds = 3
      |push.retry.poller.instances = 3
      |
      |pull.exclude.enabled = true
      |pull.exclude.email.address = "some.address@domain.com"
      |pull.exclude.email.delay.duration.seconds = 1
      |pull.exclude.email.interval.duration.minutes = 30
      |pull.exclude.older.milliseconds = 5000
      |pull.exclude.csIds.0 = eaca01f9-ec3b-4ede-b263-61b626dde232
      |pull.exclude.csIds.1 = eaca01f9-ec3b-4ede-b263-61b626dde233
      |
      |unblock.polling.enabled = true
      |unblock.polling.delay.duration.milliseconds = 400
      |
      |ttlInSeconds = 1
      |
      |push.internal.clientIds.0 = ClientIdOne
      |push.internal.clientIds.1 = ClientIdTwo
      |
      |  microservice {
      |    services {
      |      notification-queue {
      |        host = localhost
      |        port = 9648
      |        context = "/queue"
      |      }
      |      email {
      |        host = localhost
      |        port = 8300
      |        context = /hmrc/email
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
      |push.polling.delay.duration.milliseconds = 5000
      |push.polling.enabled = true
      |push.lock.duration.milliseconds = 1000
      |push.fetch.maxRecords = 50
      |
      |pull.exclude.enabled = true
      |pull.exclude.email.address = "some.address@domain.com"
      |pull.exclude.older.milliseconds = 5000
      |pull.exclude.email.delay.duration.seconds = 1
      |pull.exclude.email.interval.duration.minutes = 30
      |push.retry.enabled = true
      |push.retry.initialPollingInterval.milliseconds = 700
      |push.retry.retryAfterFailureInterval.seconds = 2
      |push.retry.inProgressRetryAfter.seconds = 3
      |push.retry.poller.instances = 3
      |
      |unblock.polling.enabled = true
      |unblock.polling.delay.duration.milliseconds = 400
      |
      |ttlInSeconds = 1
      |
      |  microservice {
      |    services {
      |      notification-queue {
      |        host = localhost
      |        port = 9648
      |        context = "/queue"
      |      }
      |      email {
      |        host = localhost
      |        port = 8300
      |        context = /hmrc/email
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

  private def testServicesConfig(configuration: Configuration) = new ServicesConfig(configuration, mock[Environment]) {
    override val mode: Mode.Value = play.api.Mode.Test
  }

  private val validServicesConfig = new Configuration(validAppConfig)
  private val mandatoryOnlyServicesConfig = new Configuration(validMandatoryOnlyAppConfig)
  private val emptyServicesConfig = new Configuration(emptyAppConfig)

  private val stubCdsLogger = StubCdsLogger()

  "ConfigService" should {
    val THOUSAND = 1000
    "return config as object model when configuration is valid" in {
      val actual = configService(validServicesConfig)

      actual.maybeBasicAuthToken shouldBe Some(basicAuthTokenValue)
      actual.notificationQueueConfig shouldBe NotificationQueueConfig("http://localhost:9648/queue")
      actual.pushNotificationConfig.internalClientIds shouldBe Seq("ClientIdOne", "ClientIdTwo")
      actual.pushNotificationConfig.lockDuration shouldBe org.joda.time.Duration.millis(THOUSAND)
      actual.pushNotificationConfig.pollingDelay shouldBe (5000 milliseconds)
      actual.pullExcludeConfig.csIdsToExclude shouldBe Seq("eaca01f9-ec3b-4ede-b263-61b626dde232", "eaca01f9-ec3b-4ede-b263-61b626dde233")
      actual.pullExcludeConfig.emailAddress shouldBe "some.address@domain.com"
      actual.pullExcludeConfig.emailUrl shouldBe "http://localhost:8300/hmrc/email"
      actual.pullExcludeConfig.pollingInterval shouldBe (30 minutes)
      actual.pushNotificationConfig.ttlInSeconds shouldBe 1
      actual.pushNotificationConfig.retryPollerEnabled shouldBe true
      actual.pushNotificationConfig.retryInitialPollingInterval shouldBe (700 milliseconds)
      actual.pushNotificationConfig.retryAfterFailureInterval shouldBe (2 seconds)
      actual.pushNotificationConfig.retryInProgressRetryAfter shouldBe (3 seconds)
      actual.pushNotificationConfig.retryPollerInstances shouldBe 3
      actual.unblockPollingConfig.pollingDelay shouldBe (400 milliseconds)
    }

    "return config as object model when configuration is valid and contains only mandatory values" in {
      val actual = configService(mandatoryOnlyServicesConfig)

      actual.maybeBasicAuthToken shouldBe None
      actual.notificationQueueConfig shouldBe NotificationQueueConfig("http://localhost:9648/queue")
      actual.pushNotificationConfig.lockDuration shouldBe org.joda.time.Duration.millis(THOUSAND)
      actual.pushNotificationConfig.pollingDelay shouldBe (5000 milliseconds)
      actual.pullExcludeConfig.notificationsOlderMillis shouldBe 5000
      actual.pushNotificationConfig.ttlInSeconds shouldBe 1
      actual.unblockPollingConfig.pollingDelay shouldBe (400 milliseconds)
      actual.pushNotificationConfig.retryPollerEnabled shouldBe true
      actual.pushNotificationConfig.retryInitialPollingInterval shouldBe (700 milliseconds)
      actual.pushNotificationConfig.retryAfterFailureInterval shouldBe (2 seconds)
      actual.pushNotificationConfig.retryInProgressRetryAfter shouldBe (3 seconds)
      actual.pushNotificationConfig.retryPollerInstances shouldBe 3
    }

    "throw an exception when configuration is invalid, that contains AGGREGATED error messages" in {
      val expected = """
                       |Could not find config notification-queue.host
                       |Service configuration not found for key: notification-queue.context
                       |Could not find config key 'push.polling.enabled'
                       |Could not find config key 'push.polling.delay.duration.milliseconds'
                       |Could not find config key 'push.lock.duration.milliseconds'
                       |Could not find config key 'push.fetch.maxRecords'
                       |Could not find config key 'ttlInSeconds'
                       |Could not find config key 'push.retry.enabled'
                       |Could not find config key 'push.retry.initialPollingInterval.milliseconds'
                       |Could not find config key 'push.retry.retryAfterFailureInterval.seconds'
                       |Could not find config key 'push.retry.inProgressRetryAfter.seconds'
                       |Could not find config key 'push.retry.poller.instances'
                       |Could not find config key 'pull.exclude.enabled'
                       |Could not find config key 'pull.exclude.email.address'
                       |Could not find config key 'pull.exclude.older.milliseconds'
                       |Could not find config email.host
                       |Service configuration not found for key: email.context
                       |Could not find config key 'pull.exclude.email.delay.duration.seconds'
                       |Could not find config key 'pull.exclude.email.interval.duration.minutes'
                       |Could not find config customs-notification-metrics.host
                       |Service configuration not found for key: customs-notification-metrics.context
                       |Could not find config key 'unblock.polling.enabled'
                       |Could not find config key 'unblock.polling.delay.duration.milliseconds'""".stripMargin

      val caught = intercept[IllegalStateException]{ configService(emptyServicesConfig) }

      caught.getMessage shouldBe expected
    }
  }

  private def configService(conf: Configuration) =
    new ConfigService(new ConfigValidatedNelAdaptor(testServicesConfig(conf), conf), stubCdsLogger)

}
