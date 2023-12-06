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

package component

import com.github.tomakehurst.wiremock.client.WireMock._
import component.ClientDataConnectorSpec.{invalidClientSubscriptionId, validPath}
import integration.IntegrationSpecBase
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.{DoNotDiscover, Suites}
import org.scalatestplus.play.ConfiguredServer
import play.api.http.Status.NOT_FOUND
import play.api.http.{HeaderNames, MimeTypes}
import play.api.test.{DefaultAwaitTimeout, FutureAwaits}
import uk.gov.hmrc.customs.notification.connectors.ClientDataConnector
import uk.gov.hmrc.customs.notification.models
import uk.gov.hmrc.customs.notification.models.{PushCallbackData, SendToPullQueue}
import util.IntegrationTestData.ApiSubsFieldsUrlContext
import util.IntegrationTestData.Stubs.stubClientDataFor
import util.TestData.Implicits._
import util.TestData._

import java.util.UUID

/**
 * Convenience class to only test this suite, as running suite directly will complain with the following:
 * "Trait ConfiguredServer needs an Application value associated with key "org.scalatestplus.play.app" in the config map."
 */
@DoNotDiscover
private class TestOnlyClientDataConnectorSpec extends Suites(new ClientDataConnectorSpec) with IntegrationSpecBase

@DoNotDiscover
class ClientDataConnectorSpec extends AnyWordSpec
  with ConfiguredServer
  with FutureAwaits
  with DefaultAwaitTimeout
  with Matchers {

  private def connector = app.injector.instanceOf[ClientDataConnector]

  "ClientDataConnector" should {
    "correctly parse client data for PushCallbackData" in {
      stubClientDataFor(ClientId, NewClientSubscriptionId, Some(ClientCallbackUrl))

      val expected =
        Right(
          ClientDataConnector.Success(
            models.ClientData(ClientId, PushCallbackData(ClientCallbackUrl, PushSecurityToken))
          )
        )

      val actual = await(connector.get(NewClientSubscriptionId))

      actual shouldBe expected
    }
    "correctly parse client data for SendToPullQueue" in {
      stubClientDataFor(ClientId, NewClientSubscriptionId, None)

      val expected =
        Right(
          ClientDataConnector.Success(
            models.ClientData(ClientId, SendToPullQueue)
          )
        )

      val actual = await(connector.get(NewClientSubscriptionId))

      actual shouldBe expected
    }

    "send the required headers" in {
      await(connector.get(NewClientSubscriptionId))

      verify(getRequestedFor(urlMatching(validPath))
        .withHeader(HeaderNames.ACCEPT, equalTo(MimeTypes.JSON))
        .withHeader(HeaderNames.CONTENT_TYPE, equalTo(MimeTypes.JSON)))
    }

    "return DeclarantNotFound when external service responds with 404 Not Found" in {
      stubFor(get(urlMatching(validPath))
        .willReturn(aResponse().withStatus(NOT_FOUND)))

      val expected = Left(ClientDataConnector.DeclarantNotFound)

      val actual = await(connector.get(invalidClientSubscriptionId))
      actual shouldBe expected
    }
  }
}

object ClientDataConnectorSpec {
  val invalidClientSubscriptionId = models.ClientSubscriptionId(UUID.fromString("00000000-0000-0000-0000-000000000000"))
  val validPath: String = ApiSubsFieldsUrlContext + "/" + NewClientSubscriptionId.toString
}