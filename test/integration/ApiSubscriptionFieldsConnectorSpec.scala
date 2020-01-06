/*
 * Copyright 2020 HM Revenue & Customs
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
import org.scalatestplus.mockito.MockitoSugar
import org.scalatestplus.play.guice.GuiceOneAppPerSuite
import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.test.Helpers._
import uk.gov.hmrc.customs.notification.connectors.ApiSubscriptionFieldsConnector
import uk.gov.hmrc.customs.notification.domain.ApiSubscriptionFields
import uk.gov.hmrc.http.{BadGatewayException, HeaderCarrier, Upstream4xxResponse, Upstream5xxResponse}
import util.ExternalServicesConfiguration.{Host, Port}
import util.TestData._
import util.{ApiSubscriptionFieldsService, ExternalServicesConfiguration, WireMockRunnerWithoutServer}

class ApiSubscriptionFieldsConnectorSpec extends IntegrationTestSpec
  with GuiceOneAppPerSuite
  with MockitoSugar
  with BeforeAndAfterAll
  with ApiSubscriptionFieldsService
  with WireMockRunnerWithoutServer {

  private lazy val connector = app.injector.instanceOf[ApiSubscriptionFieldsConnector]

  private val unexpectedHttpResponseStatus = NO_CONTENT
  private val badRequestMessage = """{"code": "BAD_REQUEST", "message": "Validation failed}"""

  private val apiSubscriptionFieldsFullUrl = wireMockUrl + apiSubscriptionFieldsUrl(validFieldsId)

  private implicit val hc: HeaderCarrier = HeaderCarrier()

  override protected def beforeAll() {
    startMockServer()
  }

  override protected def afterEach(): Unit = {
    resetMockServer()
  }

  override protected def afterAll() {
    stopMockServer()
  }

  override implicit lazy val app: Application =
    GuiceApplicationBuilder().configure(Map(
      "auditing.enabled" -> false,
      "microservice.services.api-subscription-fields.host" -> Host,
      "microservice.services.api-subscription-fields.port" -> Port,
      "microservice.services.api-subscription-fields.context" -> ExternalServicesConfiguration.ApiSubscriptionFieldsServiceContext
    )).build()

  "ApiSubscriptionFieldsServiceConnector" should {

    "make a correct request and return correct data when external service responds with 200 (OK) and payload" in {
      startApiSubscriptionFieldsService(validFieldsId)
      val expected = Some(ApiSubscriptionFields("aThirdPartyApplicationId", callbackData))

      await(connector.getClientData(validFieldsId)) shouldBe expected

      verifyApiSubscriptionFieldsServiceWasCalled(validFieldsId)
    }

    "return None when external service responds with 404 (NOT FOUND)" in {
      setupApiSubscriptionFieldsServiceToReturn(NOT_FOUND, validFieldsId)

      await(connector.getClientData(validFieldsId)) shouldBe None

      verifyApiSubscriptionFieldsServiceWasCalled(validFieldsId)
    }

    "return a failed future with IllegalStateException when external service responds with an unexpected non-error status" in {
      setupApiSubscriptionFieldsServiceToReturn(unexpectedHttpResponseStatus, validFieldsId)

      val caught = intercept[IllegalStateException](await(connector.getClientData(validFieldsId)))

      caught.getMessage shouldBe s"unexpected subscription information service response status=$unexpectedHttpResponseStatus"
      verifyApiSubscriptionFieldsServiceWasCalled(validFieldsId)
    }

    "return a failed future with IllegalStateException when external service responds with BAD REQUEST (400)" in {
      setupApiSubscriptionFieldsServiceToReturn(BAD_REQUEST, validFieldsId, responseBody = badRequestMessage)

      val caught = intercept[IllegalStateException](await(connector.getClientData(validFieldsId)))

      caught.getMessage shouldBe
        s"GET of '$apiSubscriptionFieldsFullUrl' returned 400 (Bad Request). Response body '$badRequestMessage'"
      verifyApiSubscriptionFieldsServiceWasCalled(validFieldsId)
    }

    "return a failed future with Upstream4xxResponse when external service responds with 401" in {
      setupApiSubscriptionFieldsServiceToReturn(UNAUTHORIZED, validFieldsId)

      intercept[Upstream4xxResponse](await(connector.getClientData(validFieldsId)))

      verifyApiSubscriptionFieldsServiceWasCalled(validFieldsId)
    }

    "return a failed future with Upstream5xxResponse when external service responds with 500" in {
      setupApiSubscriptionFieldsServiceToReturn(INTERNAL_SERVER_ERROR, validFieldsId)

      intercept[Upstream5xxResponse](await(connector.getClientData(validFieldsId)))

      verifyApiSubscriptionFieldsServiceWasCalled(validFieldsId)
    }

    "return a failed future with cause as BadGatewayException when it fails to connect the external service" in withoutWireMockServer {
      val caught = intercept[RuntimeException](await(connector.getClientData(validFieldsId)))

      caught.getCause.getClass shouldBe classOf[BadGatewayException]
    }
  }

}
