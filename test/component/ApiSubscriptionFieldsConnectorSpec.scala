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
import component.ApiSubscriptionFieldsConnectorSpec.{invalidClientSubscriptionId, validPath}
import integration.IntegrationBaseSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.{DoNotDiscover, Suites}
import org.scalatestplus.play.ConfiguredServer
import play.api.http.Status.{NOT_FOUND, OK}
import play.api.http.{HeaderNames, MimeTypes}
import play.api.test.{DefaultAwaitTimeout, FutureAwaits}
import uk.gov.hmrc.customs.notification.connectors.ApiSubscriptionFieldsConnector
import uk.gov.hmrc.customs.notification.models
import uk.gov.hmrc.customs.notification.models.{ApiSubscriptionFields, PushCallbackData}
import uk.gov.hmrc.http.HeaderCarrier
import util.IntegrationTestData.Responses.apiSubscriptionFieldsOkFor
import util.{IntegrationTestData, TestData}

import java.util.UUID

/**
 * Convenience class to only test this suite, as running suite directly will complain with the following:
 * "Trait ConfiguredServer needs an Application value associated with key "org.scalatestplus.play.app" in the config map."
 */
@DoNotDiscover
private class TestOnlyApiSubscriptionFieldsConnectorSpec extends Suites(new ApiSubscriptionFieldsConnectorSpec) with IntegrationBaseSpec

@DoNotDiscover
class ApiSubscriptionFieldsConnectorSpec extends AnyWordSpec
  with ConfiguredServer
  with FutureAwaits
  with DefaultAwaitTimeout
  with Matchers {

  private def connector = app.injector.instanceOf[ApiSubscriptionFieldsConnector]

  "ApiSubscriptionFieldsConnector" should {
    "parse response when external service responds with 200 OK and payload" in {
      stubFor(get(urlMatching(validPath))
        .willReturn(
          aResponse()
            .withStatus(OK)
            .withBody(apiSubscriptionFieldsOkFor(TestData.ClientId, TestData.NewClientSubscriptionId, TestData.ClientCallbackUrl))))

      val expected =
        Right(
          ApiSubscriptionFieldsConnector.Success(
            ApiSubscriptionFields(TestData.ClientId, PushCallbackData(TestData.ClientCallbackUrl, TestData.PushSecurityToken))
          )
        )

      val actual = await(connector.get(TestData.NewClientSubscriptionId)(HeaderCarrier()))

      actual shouldBe expected
    }

    "send the required headers" in {
      await(connector.get(TestData.NewClientSubscriptionId)(HeaderCarrier()))

      verify(getRequestedFor(urlMatching(validPath))
        .withHeader(HeaderNames.ACCEPT, equalTo(MimeTypes.JSON))
        .withHeader(HeaderNames.CONTENT_TYPE, equalTo(MimeTypes.JSON)))
    }

    "return DeclarantNotFound when external service responds with 404 Not Found" in {
      stubFor(get(urlMatching(validPath))
        .willReturn(aResponse().withStatus(NOT_FOUND)))

      val expected = Left(ApiSubscriptionFieldsConnector.DeclarantNotFound)

      val actual = await(connector.get(invalidClientSubscriptionId)(HeaderCarrier()))
      actual shouldBe expected
    }
  }
}

object ApiSubscriptionFieldsConnectorSpec {
  val invalidClientSubscriptionId = models.ClientSubscriptionId(UUID.fromString("00000000-0000-0000-0000-000000000000"))
  val validPath: String = IntegrationTestData.ApiSubsFieldsUrlContext + "/" + TestData.NewClientSubscriptionId.toString
}