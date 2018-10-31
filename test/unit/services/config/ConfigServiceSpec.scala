/*
 * Copyright 2018 HM Revenue & Customs
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
import uk.gov.hmrc.customs.api.common.logging.CdsLogger
import uk.gov.hmrc.customs.notification.domain.{GoogleAnalyticsSenderConfig, NotificationQueueConfig}
import uk.gov.hmrc.customs.notification.services.config.ConfigService
import uk.gov.hmrc.play.test.UnitSpec
import util.TestData.basicAuthTokenValue

import scala.concurrent.duration._
import scala.language.postfixOps

class ConfigServiceSpec extends UnitSpec with MockitoSugar with Matchers {

  private val validAppConfig: Config = ConfigFactory.parseString(
    s"""
      |{
      |auth.token.internal = "$basicAuthTokenValue"
      |
      |googleAnalytics.trackingId = UA-11111-1
      |googleAnalytics.clientId = 555
      |googleAnalytics.eventValue = 10
      |googleAnalytics.enabled = false
      |
      |push.polling.delay.duration.milliseconds = 5000
      |push.lock.duration.milliseconds = 1000
      |push.fetch.maxRecords = 50
      |
      |pull.exclude.enabled = true
      |pull.exclude.email.address = "some.address@domain.com"
      |pull.exclude.email.delay.duration.seconds = 1
      |pull.exclude.email.interval.duration.minutes = 30
      |pull.exclude.older.milliseconds = 5000
      |pull.exclude.csIds.0 = eaca01f9-ec3b-4ede-b263-61b626dde232
      |pull.exclude.csIds.1 = eaca01f9-ec3b-4ede-b263-61b626dde233
      |
      |  microservice {
      |    services {
      |      notification-queue {
      |        host = localhost
      |        port = 9648
      |        context = "/queue"
      |      }
      |      google-analytics-sender {
      |       host = localhost2
      |       port = 9822
      |       context = /send-google-analytics
      |      }
      |      email {
      |        host = localhost
      |        port = 8300
      |        context = /hmrc/email
      |      }
      |    }
      |  }
      |}
    """.stripMargin)

  private val validMandatoryOnlyAppConfig: Config = ConfigFactory.parseString(
    """
      |{
      |googleAnalytics.trackingId = UA-11111-1
      |googleAnalytics.clientId = 555
      |googleAnalytics.eventValue = 10
      |googleAnalytics.enabled = false
      |
      |push.polling.delay.duration.milliseconds = 5000
      |push.lock.duration.milliseconds = 1000
      |push.fetch.maxRecords = 50
      |
      |pull.exclude.enabled = true
      |pull.exclude.email.address = "some.address@domain.com"
      |pull.exclude.older.milliseconds = 5000
      |pull.exclude.email.delay.duration.seconds = 1
      |pull.exclude.email.interval.duration.minutes = 30
      |
      |  microservice {
      |    services {
      |      notification-queue {
      |        host = localhost
      |        port = 9648
      |        context = "/queue"
      |      }
      |      google-analytics-sender {
      |       host = localhost2
      |       port = 9822
      |       context = /send-google-analytics
      |      }
      |      email {
      |        host = localhost
      |        port = 8300
      |        context = /hmrc/email
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

  private val mockCdsLogger = mock[CdsLogger]

  "ConfigService" should {
    val THOUSAND = 1000
    "return config as object model when configuration is valid" in {
      val actual = configService(validServicesConfig)

      actual.maybeBasicAuthToken shouldBe Some(basicAuthTokenValue)
      actual.notificationQueueConfig shouldBe NotificationQueueConfig("http://localhost:9648/queue")
      actual.googleAnalyticsSenderConfig shouldBe GoogleAnalyticsSenderConfig("http://localhost2:9822/send-google-analytics", "UA-11111-1", "555", "10", gaEnabled = false)
      actual.pushNotificationConfig.lockDuration shouldBe org.joda.time.Duration.millis(THOUSAND)
      actual.pushNotificationConfig.pollingDelay shouldBe (5000 milliseconds)
      actual.pullExcludeConfig.csIdsToExclude shouldBe Seq("eaca01f9-ec3b-4ede-b263-61b626dde232", "eaca01f9-ec3b-4ede-b263-61b626dde233")
      actual.pullExcludeConfig.emailAddress shouldBe "some.address@domain.com"
      actual.pullExcludeConfig.emailUrl shouldBe "http://localhost:8300/hmrc/email"
      actual.pullExcludeConfig.pollingInterval shouldBe (30 minutes)
    }

    "return config as object model when configuration is valid and contains only mandatory values" in {
      val actual = configService(mandatoryOnlyServicesConfig)

      actual.maybeBasicAuthToken shouldBe None
      actual.notificationQueueConfig shouldBe NotificationQueueConfig("http://localhost:9648/queue")
      actual.pushNotificationConfig.lockDuration shouldBe org.joda.time.Duration.millis(THOUSAND)
      actual.pushNotificationConfig.pollingDelay shouldBe (5000 milliseconds)
      actual.pullExcludeConfig.notificationsOlderMillis shouldBe 5000
    }

    "throw an exception when configuration is invalid, that contains AGGREGATED error messages" in {
      val expected = """
      |Could not find config notification-queue.host
      |Service configuration not found for key: notification-queue.context
      |Could not find config google-analytics-sender.host
      |Service configuration not found for key: google-analytics-sender.context
      |Could not find config key 'googleAnalytics.trackingId'
      |Could not find config key 'googleAnalytics.clientId'
      |Could not find config key 'googleAnalytics.eventValue'
      |Could not find config key 'googleAnalytics.enabled'
      |Could not find config key 'push.polling.delay.duration.milliseconds'
      |Could not find config key 'push.lock.duration.milliseconds'
      |Could not find config key 'push.fetch.maxRecords'
      |Could not find config key 'pull.exclude.enabled'
      |Could not find config key 'pull.exclude.email.address'
      |Could not find config key 'pull.exclude.older.milliseconds'
      |Could not find config email.host
      |Service configuration not found for key: email.context
      |Could not find config key 'pull.exclude.email.delay.duration.seconds'
      |Could not find config key 'pull.exclude.email.interval.duration.minutes'""".stripMargin

      val caught = intercept[IllegalStateException]{ configService(emptyServicesConfig) }

      caught.getMessage shouldBe expected
    }
  }

  private def configService(conf: Configuration) =
    new ConfigService(new ConfigValidatedNelAdaptor(testServicesConfig(conf), conf), mockCdsLogger)

}
