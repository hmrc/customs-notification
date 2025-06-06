/*
 * Copyright 2024 HM Revenue & Customs
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

import org.scalatest.{BeforeAndAfterAll, Inside}
import org.scalatestplus.mockito.MockitoSugar
import org.scalatestplus.play.guice.GuiceOneAppPerSuite
import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.test.Helpers._
import uk.gov.hmrc.customs.notification.connectors.InternalPushConnector
import uk.gov.hmrc.customs.notification.controllers.CustomHeaderNames._
import uk.gov.hmrc.customs.notification.domain._
import uk.gov.hmrc.http.HeaderCarrier
import unit.logging.StubCdsLogger
import util.TestData._
import util.{ExternalServicesConfiguration, InternalPushNotificationService, WireMockRunnerWithoutServer}

import java.net.URL

class InternalPushConnectorSpec extends IntegrationTestSpec
  with GuiceOneAppPerSuite
  with MockitoSugar
  with BeforeAndAfterAll
  with InternalPushNotificationService
  with WireMockRunnerWithoutServer
  with Inside {

  private lazy val connector = app.injector.instanceOf[InternalPushConnector]
  private val stubCdsLogger = StubCdsLogger()
  private val validUrl = Some(new URL(s"http://localhost:6001${ExternalServicesConfiguration.InternalPushServiceContext}"))

  def pnr(url: Option[URL] = validUrl): PushNotificationRequest = PushNotificationRequest(
    CsidOne.id.toString,
    PushNotificationRequestBody(
      CallbackUrl(url),
      "SECURITY_TOKEN",
      conversationId.id.toString,
      Seq(
        X_CORRELATION_ID_HEADER_NAME -> correlationId,
        X_BADGE_ID_HEADER_NAME -> badgeId,
        X_SUBMITTER_ID_HEADER_NAME -> submitterNumber,
        ISSUE_DATE_TIME_HEADER -> issueDateTime
      ).map(t => Header(t._1, t._2)),
      ValidXML.toString())
  )

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
    GuiceApplicationBuilder(overrides = Seq(IntegrationTestModule(stubCdsLogger).asGuiceableModule)).configure(Map[String, Any](
      "auditing.enabled" -> false,
      "non.blocking.retry.after.minutes" -> 10
    )).build()

  "InternalPushConnector" should {

    "make a correct request" in {
      setupInternalServiceToReturn(NO_CONTENT)

      await(connector.send(pnr())(HeaderCarrier())).isRight shouldBe true

      verifyInternalServiceWasCalledWithOutboundHeaders(pnr())
    }

    "return a Left(HttpResultError) with status 300 when external service returns 300" in {
      setupInternalServiceToReturn(MULTIPLE_CHOICES)

      val result = await(connector.send(pnr())(HeaderCarrier()))

      inside(result) { case Left(HttpResultError(status, _)) =>
        status shouldBe MULTIPLE_CHOICES
      }
    }

    "return a Left(HttpResultError) with status 404 when external service returns a 404" in {
      setupInternalServiceToReturn(NOT_FOUND)

      val result = await(connector.send(pnr())(HeaderCarrier()))

      inside(result) { case Left(HttpResultError(status, _)) =>
        status shouldBe NOT_FOUND
      }
    }

    "return a Left(HttpResultError) with status 400 when external service returns a 400" in {
      setupInternalServiceToReturn(BAD_REQUEST)

      val result = await(connector.send(pnr())(HeaderCarrier()))

      inside(result) { case Left(HttpResultError(status, _)) =>
        status shouldBe BAD_REQUEST
      }
    }

    "return a Left(HttpResultError) with status 500 when external service returns a 500" in {
      setupInternalServiceToReturn(INTERNAL_SERVER_ERROR)

      val result = await(connector.send(pnr())(HeaderCarrier()))

      inside(result) { case Left(HttpResultError(status, _)) =>
        status shouldBe INTERNAL_SERVER_ERROR
      }
    }

    "return a Left(HttpResultError) with status 502 and a wrapped HttpVerb BadGatewayException when external service returns 502" in
      withoutWireMockServer {
        val result = await(connector.send(pnr())(HeaderCarrier()))

        inside(result) { case Left(HttpResultError(status, _)) =>
          status shouldBe BAD_GATEWAY
        }
      }
  }

}
