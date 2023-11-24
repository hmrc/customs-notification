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

package integration.connectors

import integration.{Spy, IntegrationBaseSpec, IntegrationRouter}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.{BeforeAndAfterEach, DoNotDiscover, Suites}
import org.scalatestplus.play.ConfiguredServer
import play.api.http.{HeaderNames, MimeTypes}
import play.api.test.{DefaultAwaitTimeout, FutureAwaits}
import uk.gov.hmrc.customs.notification.connectors.ApiSubscriptionFieldsConnector
import uk.gov.hmrc.customs.notification.models.{ApiSubscriptionFields, PushCallbackData}
import uk.gov.hmrc.http.HeaderCarrier
import util.TestData

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
  with BeforeAndAfterEach
  with DefaultAwaitTimeout
  with Matchers{

  private def connector = app.injector.instanceOf[ApiSubscriptionFieldsConnector]
  private def spy = app.injector.instanceOf[Spy]

  override def beforeEach(): Unit = {
    spy.reset()
    super.beforeEach()
  }

    "ApiSubscriptionFieldsConnector" should {
    "call the correct URL once" in {
      val expectedFieldsId = TestData.NewClientSubscriptionId

      await(connector.get(TestData.OldClientSubscriptionId)(HeaderCarrier()))

      spy.requests should have length 1
      spy.request.url shouldBe s"${IntegrationRouter.TestOrigin}/field/00000000-8888-4444-2222-111111111111"
    }

    "parse response when external service responds with 200 OK and payload" in {
      val expected =
        Right(
          ApiSubscriptionFieldsConnector.Success(
            ApiSubscriptionFields(TestData.ClientId, PushCallbackData(TestData.ClientPushUrl, TestData.PushSecurityToken))
          )
        )

      val actual = await(connector.get(TestData.OldClientSubscriptionId)(HeaderCarrier()))
      actual shouldBe expected
    }

    "send the required headers" in {
      val expectedHeaders = List(
        HeaderNames.ACCEPT -> MimeTypes.JSON,
        HeaderNames.CONTENT_TYPE -> MimeTypes.JSON
      )

      await(connector.get(TestData.OldClientSubscriptionId)(HeaderCarrier()))
      spy.request.headers should contain allElementsOf expectedHeaders
    }

    "return DeclarantNotFound when external service responds with 404 Not Found" in {
      val expected = Left(ApiSubscriptionFieldsConnector.DeclarantNotFound)

      val actual = await(connector.get(TestData.InvalidClientSubscriptionId)(HeaderCarrier()))
      actual shouldBe expected
      spy.requests should have length 1
    }
  }
}
