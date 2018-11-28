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

package integration

import org.scalatest.BeforeAndAfterAll
import org.scalatest.mockito.MockitoSugar
import org.scalatestplus.play.guice.GuiceOneAppPerSuite
import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.test.Helpers.{BAD_REQUEST, INTERNAL_SERVER_ERROR, NOT_FOUND}
import uk.gov.hmrc.customs.notification.connectors.CustomsNotificationMetricsConnector
import uk.gov.hmrc.http._
import util.CustomsNotificationMetricsTestData.ValidCustomsNotificationMetricsRequest
import util.ExternalServicesConfiguration.{Host, Port}
import util.{CustomsNotificationMetricsService, ExternalServicesConfiguration}

class CustomsNotificationMetricsConnectorSpec extends IntegrationTestSpec with GuiceOneAppPerSuite with MockitoSugar
with BeforeAndAfterAll with CustomsNotificationMetricsService {

  private lazy val connector = app.injector.instanceOf[CustomsNotificationMetricsConnector]

  private implicit val hc: HeaderCarrier = HeaderCarrier()

  override protected def beforeAll() {
    startMockServer()
  }

  override protected def beforeEach() {
    resetMockServer()
  }

  override protected def afterAll() {
    stopMockServer()
  }

//  ,

//  override implicit lazy val app: Application =
//    GuiceApplicationBuilder().configure(Map(
//      "auditing.consumer.baseUri.host" -> Host,
//      "auditing.consumer.baseUri.port" -> Port,
//      "auditing.enabled" -> false
//    )).build()

  override implicit lazy val app: Application = GuiceApplicationBuilder().configure(Map(
    "microservice.services.customs-notification-metrics.host" -> Host,
    "microservice.services.customs-notification-metrics.port" -> Port,
    "microservice.services.customs-notification-metrics.context" -> ExternalServicesConfiguration.CustomsNotificationMetricsContext
  )).build()

  "MetricsConnector" should {

    "make a correct request" in {
      setupCustomsNotificationMetricsServiceToReturn()

      val response: Unit = await(sendValidRequest())

      response shouldBe ()
      verifyFileTransmissionServiceWasCalledWith(ValidCustomsNotificationMetricsRequest)

    }

    "return a failed future when external service returns 404" in {
      setupCustomsNotificationMetricsServiceToReturn(NOT_FOUND)

      intercept[RuntimeException](await(sendValidRequest())).getCause.getClass shouldBe classOf[NotFoundException]
    }

    "return a failed future when external service returns 400" in {
      setupCustomsNotificationMetricsServiceToReturn(BAD_REQUEST)

      intercept[RuntimeException](await(sendValidRequest())).getCause.getClass shouldBe classOf[BadRequestException]
    }

    "return a failed future when external service returns 500" in {
      setupCustomsNotificationMetricsServiceToReturn(INTERNAL_SERVER_ERROR)

      intercept[Upstream5xxResponse](await(sendValidRequest()))
    }

    "return a failed future when fail to connect the external service" in {
      stopMockServer()

      intercept[RuntimeException](await(sendValidRequest())).getCause.getClass shouldBe classOf[BadGatewayException]
      startMockServer()
    }

  }

  private def sendValidRequest() = {
    connector.post(ValidCustomsNotificationMetricsRequest)
  }
}
