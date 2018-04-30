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
import play.api.{Configuration, Environment}
import uk.gov.hmrc.customs.api.common.config.{ConfigValidationNelAdaptor, ServicesConfig}
import uk.gov.hmrc.customs.api.common.logging.CdsLogger
import uk.gov.hmrc.customs.notification.domain.NotificationQueueConfig
import uk.gov.hmrc.customs.notification.services.config.ConfigService
import uk.gov.hmrc.play.test.UnitSpec
import util.TestData.basicAuthTokenValue

class ConfigServiceSpec extends UnitSpec with MockitoSugar with Matchers {

  private val validAppConfig: Config = ConfigFactory.parseString(
    s"""
      |{
      |auth.token.internal = "$basicAuthTokenValue"
      |
      |  microservice {
      |    services {
      |      notification-queue {
      |        host = localhost
      |        port = 9648
      |        context = "/queue"
      |      }
      |    }
      |  }
      |}
    """.stripMargin)

  private val validMandatoryOnlyAppConfig: Config = ConfigFactory.parseString(
    """
      |{
      |  microservice {
      |    services {
      |      notification-queue {
      |        host = localhost
      |        port = 9648
      |        context = "/queue"
      |      }
      |    }
      |  }
      |}
    """.stripMargin)

  private val emptyAppConfig: Config = ConfigFactory.parseString("")

  private def testServicesConfig(configuration: Configuration) = new ServicesConfig(configuration, mock[Environment]) {
    override val mode = play.api.Mode.Test
  }

  private val validServicesConfig = new Configuration(validAppConfig)
  private val mandatoryOnlyServicesConfig = new Configuration(validMandatoryOnlyAppConfig)
  private val emptyServicesConfig = new Configuration(emptyAppConfig)

  private val mockCdsLogger = mock[CdsLogger]

  "ConfigService" should {
    "return config as object model when configuration is valid" in {
      val actual = configService(validServicesConfig)

      actual.maybeBasicAuthToken shouldBe Some(basicAuthTokenValue)
      actual.notificationQueueConfig shouldBe NotificationQueueConfig("http://localhost:9648/queue")
    }

    "return config as object model when configuration is valid and contains only mandatory values" in {
      val actual = configService(mandatoryOnlyServicesConfig)

      actual.maybeBasicAuthToken shouldBe None
      actual.notificationQueueConfig shouldBe NotificationQueueConfig("http://localhost:9648/queue")
    }

    "throw an exception when configuration is invalid, that contains AGGREGATED error messages" in {
      val expected = """
      |Could not find config notification-queue.host
      |Service configuration not found for key: notification-queue.context""".stripMargin

      val caught = intercept[IllegalStateException]{ configService(emptyServicesConfig) }

      caught.getMessage shouldBe expected
    }
  }

  private def configService(conf: Configuration) =
    new ConfigService(new ConfigValidationNelAdaptor(testServicesConfig(conf), conf), mockCdsLogger)

}
