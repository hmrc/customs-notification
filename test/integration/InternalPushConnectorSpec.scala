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

package integration

import org.mockito.Mockito._
import org.scalatest.BeforeAndAfterAll
import org.scalatest.mockito.MockitoSugar
import org.scalatestplus.play.guice.GuiceOneAppPerSuite
import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.test.Helpers._
import uk.gov.hmrc.customs.api.common.logging.CdsLogger
import uk.gov.hmrc.customs.notification.connectors.InternalPushConnector
import uk.gov.hmrc.customs.notification.controllers.CustomHeaderNames._
import uk.gov.hmrc.customs.notification.domain.{Header, HttpResultError, PushNotificationRequest, PushNotificationRequestBody}
import unit.services.ClientWorkerTestData.CsidOne
import util.TestData._
import util.{ExternalServicesConfiguration, InternalPushNotificationService}

class InternalPushConnectorSpec extends IntegrationTestSpec with GuiceOneAppPerSuite with MockitoSugar
  with BeforeAndAfterAll with InternalPushNotificationService {

  private lazy val connector = app.injector.instanceOf[InternalPushConnector]
  private val mockLogger = mock[CdsLogger]

  private val pnr = PushNotificationRequest(
    CsidOne.id.toString,
    PushNotificationRequestBody(s"http://localhost:11111${ExternalServicesConfiguration.InternalPushServiceContext}",
      "SECURITY_TOKEN",
      conversationId.id.toString,
      Seq(
        X_CORRELATION_ID_HEADER_NAME -> correlationId,
        X_BADGE_ID_HEADER_NAME -> badgeId,
        X_EORI_ID_HEADER_NAME -> eoriNumber
      ).map(t => Header(t._1, t._2)),
      ValidXML.toString())
  )

  override protected def beforeAll() {
    startMockServer()
    reset(mockLogger)
  }

  override protected def afterEach(): Unit = {
    resetMockServer()
  }

  override protected def afterAll() {
    stopMockServer()
  }

  override implicit lazy val app: Application =
    GuiceApplicationBuilder(overrides = Seq(IntegrationTestModule(mockLogger).asGuiceableModule)).configure(Map(
      "auditing.enabled" -> false
    )).build()

  "InternalPushConnector" should {

    "make a correct request" in {
      setupInternalServiceToReturn(NO_CONTENT)

      val Right(_) = await(connector.send(pnr))

      verifyInternalServiceWasCalledWithOutboundHeaders(pnr)
    }

    "return a Left(HttpResultError) with status 404 and a wrapped HttpVerb NotFoundException when external service returns 404" in {
      setupInternalServiceToReturn(NOT_FOUND)

      val Left(httpResultError: HttpResultError) = await(connector.send(pnr))

      httpResultError.status shouldBe NOT_FOUND
    }

    "return a Left(HttpResultError) with status 400 and a wrapped HttpVerb BadRequestException when external service returns 400" in {
      setupInternalServiceToReturn(BAD_REQUEST)

      val Left(httpResultError: HttpResultError) = await(connector.send(pnr))

      httpResultError.status shouldBe BAD_REQUEST
    }

    "return a Left(HttpResultError) with status 500 and a wrapped HttpVerb Upstream5xxResponse when external service returns 500" in {
      setupInternalServiceToReturn(INTERNAL_SERVER_ERROR)

      val Left(httpResultError: HttpResultError) = await(connector.send(pnr))

      httpResultError.status shouldBe INTERNAL_SERVER_ERROR
    }


    "return a Left(HttpResultError) with status 502 and a wrapped HttpVerb BadGatewayException when external service returns 502" in
      withoutWireMockServer {
        val Left(httpResultError: HttpResultError) = await(connector.send(pnr))

        httpResultError.status shouldBe BAD_GATEWAY
      }
  }

}
