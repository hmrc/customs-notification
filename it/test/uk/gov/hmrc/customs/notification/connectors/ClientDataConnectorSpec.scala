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

package uk.gov.hmrc.customs.notification.connectors

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.*
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.{DoNotDiscover, Suite}
import org.scalatestplus.play.ConfiguredServer
import play.api.http.Status.{NOT_FOUND, OK}
import play.api.http.{HeaderNames, MimeTypes}
import uk.gov.hmrc.customs.notification.models.{PushCallbackData, SendToPullQueue}
import uk.gov.hmrc.customs.notification.util.IntegrationTestHelpers.PathFor.clientDataWithCsid
import uk.gov.hmrc.customs.notification.util.TestData.*
import uk.gov.hmrc.customs.notification.util.TestData.Implicits.*
import uk.gov.hmrc.customs.notification.util.WireMockHelpers
import uk.gov.hmrc.customs.notification.{IntegrationSpecBase, models}

import java.util.UUID

/**
 * Convenience class to only test this suite, as no app is available when running suite directly
 */
@DoNotDiscover
private class TestOnlyClientDataConnectorSpec extends Suite with IntegrationSpecBase {
  override def nestedSuites: IndexedSeq[Suite] =
    Vector(new ClientDataConnectorSpec(wireMockServer))
}

class ClientDataConnectorSpec(val wireMockServer: WireMockServer) extends AnyWordSpec
  with ConfiguredServer
  with WireMockHelpers
  with ScalaFutures
  with IntegrationPatience
  with Matchers {

  private val invalidClientSubscriptionId = models.ClientSubscriptionId(UUID.fromString("00000000-0000-0000-0000-000000000000"))

  private def connector = app.injector.instanceOf[ClientDataConnector]

  "ClientDataConnector" should {
    "correctly parse client data for PushCallbackData" in {
      stubGetClientDataOk()

      val expected =
        Right(
          ClientDataConnector.Success(
            models.ClientData(ClientId, PushCallbackData(ClientCallbackUrl, PushSecurityToken))
          )
        )

      val actual = connector.get(TranslatedCsid).futureValue

      actual shouldBe expected
    }
    "correctly parse client data for SendToPullQueue" in {
      stub(get)(clientDataWithCsid(), OK, stubClientDataResponseBody(callbackUrl = None))

      val expected =
        Right(
          ClientDataConnector.Success(
            models.ClientData(ClientId, SendToPullQueue)
          )
        )

      val actual = connector.get(TranslatedCsid).futureValue
      actual shouldBe expected
    }

    "send the required headers" in {
      stubGetClientDataOk()
      connector.get(TranslatedCsid).futureValue

      verify(getRequestedFor(urlMatching(clientDataWithCsid()))
        .withHeader(HeaderNames.ACCEPT, equalTo(MimeTypes.JSON))
        .withHeader(HeaderNames.CONTENT_TYPE, equalTo(MimeTypes.JSON)))
    }

    "return DeclarantNotFound when external service responds with 404 Not Found" in {
      stubFor(get(urlMatching(clientDataWithCsid()))
        .willReturn(aResponse().withStatus(NOT_FOUND)))

      val expected = Left(ClientDataConnector.DeclarantNotFound)

      val actual = connector.get(invalidClientSubscriptionId).futureValue
      actual shouldBe expected
    }
  }
}
