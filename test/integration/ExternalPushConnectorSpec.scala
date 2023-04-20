/*
 * Copyright 2023 HM Revenue & Customs
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
import uk.gov.hmrc.customs.notification.connectors.ExternalPushConnector
import uk.gov.hmrc.customs.notification.controllers.CustomHeaderNames.NOTIFICATION_ID_HEADER_NAME
import uk.gov.hmrc.customs.notification.domain.HttpResultError
import uk.gov.hmrc.http.HeaderCarrier
import util.ExternalServicesConfiguration.{Host, Port}
import util.TestData._
import util.{ExternalServicesConfiguration, PushNotificationService, WireMockRunnerWithoutServer}

class ExternalPushConnectorSpec extends IntegrationTestSpec
  with GuiceOneAppPerSuite
  with MockitoSugar
  with BeforeAndAfterAll
  with PushNotificationService
  with WireMockRunnerWithoutServer {

  private lazy val connector = app.injector.instanceOf[ExternalPushConnector]

  override protected def beforeAll(): Unit = {
    startMockServer()
  }

  override protected def afterEach(): Unit = {
    resetMockServer()
  }

  override protected def afterAll(): Unit = {
    stopMockServer()
  }

  override implicit lazy val app: Application =
    GuiceApplicationBuilder().configure(Map(
      "auditing.enabled" -> false,
      "microservice.services.public-notification.host" -> Host,
      "microservice.services.public-notification.port" -> Port,
      "microservice.services.public-notification.context" -> ExternalServicesConfiguration.PushNotificationServiceContext,
      "non.blocking.retry.after.minutes" -> 10
    )).build()

  "ExternalPushConnector" should {

    "make a correct request" in {
      setupPushNotificationServiceToReturn(NO_CONTENT)

      val Right(_) = await(connector.send(externalPushNotificationRequest)(HeaderCarrier().withExtraHeaders((NOTIFICATION_ID_HEADER_NAME,notificationId.toString)), requestMetaData))

      verifyPushNotificationServiceWasCalledWith(externalPushNotificationRequest)
    }

    "return a Left(HttpResultError) with status 300 when external service returns 300" in {
      setupPushNotificationServiceToReturn(MULTIPLE_CHOICES)

      val Left(httpResultError: HttpResultError) = await(connector.send(externalPushNotificationRequest)(HeaderCarrier(), requestMetaData))

      httpResultError.status shouldBe MULTIPLE_CHOICES
    }

    "return a Left(HttpResultError) with status 404 when external service returns a 404" in {
      setupPushNotificationServiceToReturn(NOT_FOUND)

      val Left(httpResultError: HttpResultError) = await(connector.send(externalPushNotificationRequest)(HeaderCarrier(), requestMetaData))

      httpResultError.status shouldBe NOT_FOUND
    }

    "return a Left(HttpResultError) with status 400 when external service returns a 400" in {
      setupPushNotificationServiceToReturn(BAD_REQUEST)

      val Left(httpResultError: HttpResultError) = await(connector.send(externalPushNotificationRequest)(HeaderCarrier(), requestMetaData))

      httpResultError.status shouldBe BAD_REQUEST
    }

    "return a Left(HttpResultError) with status 500 when external service returns a 500" in {
      setupPushNotificationServiceToReturn(INTERNAL_SERVER_ERROR)

      val Left(httpResultError: HttpResultError) = await(connector.send(externalPushNotificationRequest)(HeaderCarrier(), requestMetaData))

      httpResultError.status shouldBe INTERNAL_SERVER_ERROR
    }

    "return a Left(HttpResultError) with status 502 and a wrapped HttpVerb BadGatewayException when external service returns 502" in
      withoutWireMockServer {
        val Left(httpResultError: HttpResultError) = await(connector.send(externalPushNotificationRequest)(HeaderCarrier(), requestMetaData))

        httpResultError.status shouldBe BAD_GATEWAY
      }
  }

}
