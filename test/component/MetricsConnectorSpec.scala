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
import integration.IntegrationSpecBase
import org.scalatest._
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.play.ConfiguredServer
import play.api.http.MimeTypes
import play.api.libs.json.Json
import play.api.test.Helpers.{ACCEPT, CONTENT_TYPE}
import play.api.test.{DefaultAwaitTimeout, FutureAwaits}
import uk.gov.hmrc.customs.notification.connectors.MetricsConnector
import util.TestData.Implicits._
import util.TestData._
import util.{IntegrationTestData, TestData}

/**
 * Convenience class to only test this suite, as running suite directly will complain with the following:
 * "Trait ConfiguredServer needs an Application value associated with key "org.scalatestplus.play.app" in the config map."
 */
@DoNotDiscover
private class TestOnlySendMetricsConnectorSpec extends Suites(new MetricsConnectorSpec) with IntegrationSpecBase

@DoNotDiscover
class MetricsConnectorSpec extends AnyWordSpec
  with ConfiguredServer
  with FutureAwaits
  with DefaultAwaitTimeout
  with Matchers {

  private def connector = app.injector.instanceOf[MetricsConnector]

  "MetricsConnector when sending a request" should {
    "send the correct headers" in {
      await(connector.send(Notification))

      verify(postRequestedFor(urlMatching(IntegrationTestData.MetricsUrlContext))
        .withHeader(ACCEPT, equalTo(MimeTypes.JSON))
        .withHeader(CONTENT_TYPE, equalTo(MimeTypes.JSON)))
    }

    "send the correct body" in {
      await(connector.send(Notification))

      verify(postRequestedFor(urlMatching(IntegrationTestData.MetricsUrlContext))
        .withRequestBody(equalToJson(
          Json.obj(
            "eventType" -> "NOTIFICATION",
            "conversationId" -> TestData.ConversationId.toString,
            "eventStart" -> TestData.TimeNow,
            "eventEnd" -> TestData.TimeNow
          ).toString
        )))
    }
  }
}
